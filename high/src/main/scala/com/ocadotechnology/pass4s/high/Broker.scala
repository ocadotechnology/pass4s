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

package com.ocadotechnology.pass4s.high

import cats.ApplicativeThrow
import cats.MonadThrow
import cats.effect.Resource
import cats.effect.implicits.*
import cats.effect.kernel.Async
import cats.implicits.*
import com.ocadotechnology.pass4s.core.Connector
import com.ocadotechnology.pass4s.core.Destination
import com.ocadotechnology.pass4s.core.End
import com.ocadotechnology.pass4s.core.Message
import com.ocadotechnology.pass4s.core.Message.Payload
import com.ocadotechnology.pass4s.core.Source
import com.ocadotechnology.pass4s.kernel.*
import fs2.Stream
import izumi.reflect.Tag

trait Broker[F[_], +P] {
  def consumer[R >: P](source: Source[R]): Consumer[F, Payload]

  def sender[R >: P]: Sender[F, Message[R]]
}

object Broker {

  def apply[F[_], P](
    implicit ev: Broker[F, P]
  ): ev.type = ev

  def fromConnector[F[_]: Async, P](connector: Connector[F, P]): Broker[F, P] =
    new Broker[F, P] {

      override def consumer[R >: P](source: Source[R]): Consumer[F, Payload] = {
        val toConsumer: Stream[F, List[Resource[F, Payload]]] => Consumer[F, Payload] =
          if (source.maxConcurrent === 1) s => Consumer.sequential(source.cancelableMessageProcessing)(s.flatMap(Stream.emits))
          else s => Consumer.paralleled(source.maxConcurrent, source.cancelableMessageProcessing)(s.flatMap(Stream.emits))
        // TODO: if we ever figure out how to properly do batched FIFO with retries, add a case here

        val consumer = toConsumer(connector.consumeBatched(source).map(_.map(_.scope)))
        source.messageProcessingTimeout match {
          case Some(timeout) => consumer.surroundEach(_.timeout(timeout))
          case None          => consumer
        }
      }

      override def sender[R >: P]: Sender[F, Message[R]] =
        Sender.fromFunction(connector.produce[R])
    }

  def routed[F[_], P](chooseBroker: End[P] => Broker[F, P]): Broker[F, P] =
    new Broker[F, P] {
      override def consumer[R >: P](source: Source[R]): Consumer[F, Payload] =
        chooseBroker(source.asInstanceOf[Source[P]]).consumer(source)

      override def sender[R >: P]: Sender[F, Message[R]] =
        Sender.routedBy((_: Message[R]).destination) { destination =>
          chooseBroker(destination.asInstanceOf[Destination[P]]).sender[R]
        }

    }

  def mergeByCapabilities[F[_]: ApplicativeThrow, P1: Tag, P2: Tag](
    broker1: Broker[F, P1],
    broker2: Broker[F, P2]
  ): F[Broker[F, P1 & P2]] = {
    val commonCapabilities = Tag[P1].tag.decompose intersect Tag[P2].tag.decompose
    if (commonCapabilities.nonEmpty)
      ApplicativeThrow[F].raiseError(
        new IllegalArgumentException(
          s"While merging, brokers can not have common capability. Broker 1: ${Tag[P1]}, Broker 2: ${Tag[P2]}, Common: $commonCapabilities"
        )
      )
    else
      new Broker[F, P1 & P2] {
        override def consumer[R >: P1 & P2](source: Source[R]): Consumer[F, Payload] =
          source.capability match {
            case p1 if Tag[P1].tag <:< p1 => broker1.asInstanceOf[Broker[F, R]].consumer(source)
            case p2 if Tag[P2].tag <:< p2 => broker2.asInstanceOf[Broker[F, R]].consumer(source)
            case _                        =>
              throw new UnsupportedOperationException(
                s"Broker with capabilities [${Tag[P1]} & ${Tag[P2]}] doesn't support capability ${source.capability} from $source"
              )
          }

        override def sender[R >: P1 & P2]: Sender[F, Message[R]] =
          Sender.routed { message =>
            message.destination.capability match {
              case p1 if Tag[P1].tag <:< p1 => broker1.asInstanceOf[Broker[F, R]].sender
              case p2 if Tag[P2].tag <:< p2 => broker2.asInstanceOf[Broker[F, R]].sender
              case _                        =>
                throw new UnsupportedOperationException(
                  s"Broker with capabilities [${Tag[P1]} & ${Tag[P2]}] doesn't support capability ${message.destination.capability} from $message"
                )
            }
          }
      }.pure[F]
  }

  // boring stuff

  def mergeByCapabilities[F[_]: MonadThrow, P1: Tag, P2: Tag, P3: Tag](
    broker1: Broker[F, P1],
    broker2: Broker[F, P2],
    broker3: Broker[F, P3]
  ): F[Broker[F, P1 & P2 & P3]] =
    mergeByCapabilities[F, P1, P2](broker1, broker2)
      .flatMap(mergeByCapabilities[F, P1 & P2, P3](_, broker3))

  def mergeByCapabilities[F[_]: MonadThrow, P1: Tag, P2: Tag, P3: Tag, P4: Tag](
    broker1: Broker[F, P1],
    broker2: Broker[F, P2],
    broker3: Broker[F, P3],
    broker4: Broker[F, P4]
  ): F[Broker[F, P1 & P2 & P3 & P4]] =
    mergeByCapabilities[F, P1, P2, P3](broker1, broker2, broker3)
      .flatMap(mergeByCapabilities[F, P1 & P2 & P3, P4](_, broker4))

  def mergeByCapabilities[F[_]: MonadThrow, P1: Tag, P2: Tag, P3: Tag, P4: Tag, P5: Tag](
    broker1: Broker[F, P1],
    broker2: Broker[F, P2],
    broker3: Broker[F, P3],
    broker4: Broker[F, P4],
    broker5: Broker[F, P5]
  ): F[Broker[F, P1 & P2 & P3 & P4 & P5]] =
    mergeByCapabilities[F, P1, P2, P3, P4](broker1, broker2, broker3, broker4)
      .flatMap(mergeByCapabilities[F, P1 & P2 & P3 & P4, P5](_, broker5))

}
