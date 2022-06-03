package com.ocadotechnology.pass4s.demo

import cats.effect.ExitCode
import cats.effect.IO
import cats.effect.IOApp
import cats.effect.kernel.Async
import cats.effect.kernel.Sync
import cats.effect.kernel.Temporal
import cats.implicits._
import com.ocadotechnology.pass4s.circe.syntax._
import com.ocadotechnology.pass4s.connectors.activemq.Jms
import com.ocadotechnology.pass4s.connectors.activemq.JmsSource
import com.ocadotechnology.pass4s.core.CommittableMessage
import com.ocadotechnology.pass4s.core.Connector
import com.ocadotechnology.pass4s.core.Message
import com.ocadotechnology.pass4s.core.Message.Payload
import com.ocadotechnology.pass4s.core.Source
import com.ocadotechnology.pass4s.high.Broker
import com.ocadotechnology.pass4s.logging.syntax._
import fs2.Pure
import fs2.Stream
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.slf4j.Slf4jLogger

object CirceDemo extends IOApp {
  def msg(i: Int) = s"""{"s": $i}"""
  final case class Foo(s: Int)

  def makeMessage[F[_]: Sync](msg: String, console: String => F[Unit]): CommittableMessage[F] =
    CommittableMessage.instance(
      Payload(msg, Map.empty),
      console(s"Committing $msg"),
      cause => console(s"Rolling back $msg with cause $cause")
    )

  def rawMessages[F[_]: Sync](console: String => F[Unit]): Stream[Pure, CommittableMessage[F]] =
    Stream
      .iterate(0)(_ + 1)
      .map { i =>
        makeMessage(msg(i), console)
      }

  def runDemo[F[_]: Async](console: String => F[Unit]): F[ExitCode] = {

    import io.circe.generic.auto._

    val connector: Connector.Aux[F, Jms, Unit] = new Connector[F, Jms] {
      override type Raw = Unit

      override def underlying: Unit = ()

      override def consumeBatched[R >: Jms](source: Source[R]): Stream[F, List[CommittableMessage[F]]] =
        rawMessages[F](console).take(10).map(List(_))

      override def produce[R >: Jms](message: Message[R]): F[Unit] = ???
    }
    Broker
      .fromConnector(connector)
      .consumer(JmsSource.queue("fake"))
      .asJsonConsumerWithMessage[Foo]
      .consume { result =>
        console(s"Result: $result")
      }
      .as(ExitCode.Success)
  }

  def runDemoConcurrent[F[_]: Async](console: String => F[Unit]): F[ExitCode] = {
    import cats.effect.implicits._

    import scala.concurrent.duration._

    def msg(i: Int) = s"""{"s": $i}"""

    val connector: Connector.Aux[F, Jms, Unit] = new Connector[F, Jms] {
      override type Raw = Unit

      override def underlying: Unit = ()

      override def consumeBatched[R >: Jms](source: Source[R]): Stream[F, List[CommittableMessage[F]]] =
        Stream.iterate(0)(_ + 1).map(i => List(makeMessage(msg(i), console)))

      override def produce[R >: Jms](message: Message[R]): F[Unit] = ???
    }

    implicit val logger: Logger[F] = Slf4jLogger.getLogger[F]

    Broker
      .fromConnector(connector)
      .logged
      .consumer(JmsSource.queue("fake2"))
      .consume { result =>
        console(s"Starting: $result") *> Temporal[F].sleep(30.millis) *> console(s"Result: $result")
      }
      .timeout(1.second)
      .as(ExitCode.Success)
  }

  def run(args: List[String]): IO[ExitCode] =
    // runDemo[IO](a => IO(println(a)))
    runDemoConcurrent[IO](a => IO(println(a)))

}
