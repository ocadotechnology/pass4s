/*
 * Copyright 2023 Ocado Technology
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.ocadotechnology.pass4s.demo

import cats.Foldable
import cats.Monad
import cats.data.Chain
import cats.data.Kleisli
import cats.data.NonEmptyList
import cats.data.WriterT
import cats.effect.Concurrent
import cats.effect.ExitCode
import cats.effect.IO
import cats.effect.IOApp
import cats.effect.Resource
import cats.effect.implicits._
import cats.implicits._
import cats.~>
import com.ocadotechnology.pass4s.circe.syntax._
import com.ocadotechnology.pass4s.connectors.activemq.Jms
import com.ocadotechnology.pass4s.connectors.activemq.JmsConnector
import com.ocadotechnology.pass4s.core._
import com.ocadotechnology.pass4s.extra.MessageProcessor
import com.ocadotechnology.pass4s.high._
import com.ocadotechnology.pass4s.kernel._
import com.ocadotechnology.pass4s.logging.syntax._
import fs2.Pipe
import fs2.Stream
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.slf4j.Slf4jLogger

import java.util.UUID

object DemoMain extends IOApp {

  type ConnectionIO[A] = Kleisli[IO, String, A]
  type AppEffect[A] = WriterT[ConnectionIO, Chain[Message[Jms]], A]

  val transactorResource: Resource[IO, ConnectionIO ~> IO] = Resource.pure[IO, ConnectionIO ~> IO](λ[ConnectionIO ~> IO] { cio =>
    IO(UUID.randomUUID()).map(_.toString()).flatTap(s => IO(println("Transaction ID: " + s))).flatMap(cio.run)
  })

  def runAppEffect[F[_]: Monad](sender: Sender[F, Message[Jms]])(transactor: ConnectionIO ~> F): AppEffect ~> F =
    WriterT
      .liftFunctionK(transactor)
      .andThen(sender.sendWrittenK)

  object outboxPattern {

    def run[F[_]: Concurrent, C[_]: Foldable](
      findMessages: F[C[Message[Jms]]]
    )(
      sender: Sender[F, Message[Jms]]
    )(
      // this could be made into something like "if there's a lot of traffic, fetch every 10ms, but if there isn't grow exponentially up to 5s"
      delay: Pipe[F, C[Message[Jms]], C[Message[Jms]]]
    ): Resource[F, Unit] =
      Stream
        .repeatEval(findMessages)
        .through(delay)
        .flatMap(fs2.Stream.foldable(_))
        .through(sender.send)
        .compile
        .drain
        .background
        .void

    def sender: Sender[ConnectionIO, Message[Jms]] = ??? /* save to repo */

    def findMessages: ConnectionIO[List[Message[Jms]]] = ??? /* use repo */
  }

  //
  //
  //
  // less boring stuff
  //
  //
  //

  val brokerResource = Pekko
    .system[IO]
    .flatMap { implicit sys =>
      implicit val connectorLogger: Logger[IO] = Slf4jLogger.getLoggerFromClass[IO](classOf[Connector[IO, Jms]])

      JmsConnector
        .singleBroker[IO](
          "admin",
          "admin",
          "failover:(tcp://localhost:61616)"
        )
        .map(_.logged)
    }
    .map(Broker.fromConnector[IO, Jms])
    .map { broker =>
      implicit val brokerLogger: Logger[IO] = Slf4jLogger.getLoggerFromClass[IO](classOf[Broker[IO, Jms]])
      broker.logged
    }

  object senders {
    private val writerSender: Sender[AppEffect, Message[Jms]] = Sender.writer: Sender[AppEffect, Message[Jms]]

    implicit val senderInts: Sender[AppEffect, Int] = writerSender.asJsonSender(Destinations.orderUpdates)
    implicit val senderBools: Sender[AppEffect, Boolean] = writerSender.asJsonSender(Destinations.orderEvents)
  }

  def run(args: List[String]): IO[ExitCode] = {
    val server = for {
      broker     <- brokerResource
      transactor <- transactorResource
    } yield {
      val runEffect = runAppEffect(broker.sender)(transactor)

      import senders._

      implicit val service: MyService[AppEffect] = MyService.instance[AppEffect]

      // just in case someone wants to run this in a router, they can mapK as usual (here or on router level)
      val ioService: MyService[IO] = service.mapK(runEffect)

      val _ = ioService

      NonEmptyList
        .of(
          broker
            .consumer(Destinations.inventoryEvents)
            .asJsonConsumer[String]
            .consumeCommitK(runEffect)(MyProcessor.instance[AppEffect])
            .background
            .void,
          MessageProcessor
            .init[IO]
            .enrich(_.asJsonConsumer[String])
            .transacted(runEffect)
            .bindBroker(broker)
            .handle(Destinations.inventoryEvents)(MyProcessor.instance[AppEffect])
        )
        .sequence_
    }

    server.flatten.surround(IO.never) /* .timeout(3.seconds) */.as(ExitCode.Success)
  }

}
