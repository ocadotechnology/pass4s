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

package com.ocadotechnology.pass4s.logging

import cats.Monad
import cats.effect.Resource
import cats.effect.implicits.*
import cats.effect.kernel.MonadCancelThrow
import cats.effect.kernel.Outcome
import cats.effect.kernel.Resource.ExitCase
import cats.syntax.all.*
import com.ocadotechnology.pass4s.core.CommittableMessage
import com.ocadotechnology.pass4s.core.Connector
import com.ocadotechnology.pass4s.core.Message
import com.ocadotechnology.pass4s.core.Message.Payload
import com.ocadotechnology.pass4s.core.Source
import com.ocadotechnology.pass4s.high.Broker
import com.ocadotechnology.pass4s.kernel.Consumer
import com.ocadotechnology.pass4s.kernel.Sender
import fs2.Stream
import org.typelevel.log4cats.Logger

object syntax {

  implicit final class LoggingSenderSyntax[F[_], P](private val underlying: Sender[F, Message[P]]) extends AnyVal {

    def logged(
      implicit logger: Logger[F],
      F: MonadCancelThrow[F]
    ): Sender[F, Message[P]] = {
      val logBefore = Sender.fromFunction[F, Message[P]] { msg =>
        logger.trace(s"Sending message to destination [${msg.destination}]: [${msg.payload}]")
      }

      val logAfter = Sender.fromFunction[F, Message[P]] { msg =>
        logger.trace(s"Sent message to destination [${msg.destination}]: [${msg.payload}]")
      }

      logBefore |+| underlying |+| logAfter
    }

  }

  implicit final class LoggingConsumerSyntax[F[_], A](private val underlying: Consumer[F, Payload]) extends AnyVal {

    def logged[P](
      source: Source[P]
    )(
      implicit logger: Logger[F],
      F: MonadCancelThrow[F]
    ): Consumer[F, Payload] =
      underlying
        .surroundAll { run =>
          logger.info(s"Starting consumer from [$source]") *> run.guaranteeCase {
            case Outcome.Succeeded(_) => logger.info(s"Stopping consumer from [$source] normally")
            case Outcome.Canceled()   => logger.warn(s"Stopping consumer for [$source] because of cancelation")
            case Outcome.Errored(e)   => logger.error(e)(s"Stopping consumer for [$source] due to an error")
          }
        }
        .surroundEachWith { msg => handle =>
          logger.debug(s"Received message [$msg] from [$source]") *>
            handle.guaranteeCase {
              case Outcome.Succeeded(_) => logger.debug(s"Committing message [$msg]")
              case Outcome.Canceled()   => logger.warn(s"Rolling back message [$msg] because of cancelation")
              case Outcome.Errored(e)   => logger.error(e)(s"Rolling back message [$msg] because of an error")
            }
        }

  }

  implicit final class LoggingBrokerSyntax[F[_], P](private val underlying: Broker[F, P]) extends AnyVal {

    def logged(
      implicit logger: Logger[F],
      F: MonadCancelThrow[F]
    ): Broker[F, P] =
      new Broker[F, P] {
        override def consumer[R >: P](source: Source[R]): Consumer[F, Payload] =
          underlying.consumer(source).logged(source)

        override def sender[R >: P]: Sender[F, Message[R]] =
          underlying.sender[R].logged
      }

  }

  implicit final class ConnectorLoggingSyntax[F[_], P](val self: Connector[F, P]) extends AnyVal {

    def logged(
      implicit logger: Logger[F],
      F: Monad[F]
    ): Connector.Aux[F, P, self.Raw] =
      new Connector[F, P] {
        type Raw = self.Raw
        val underlying: self.Raw = self.underlying

        override def consumeBatched[R >: P](source: Source[R]): Stream[F, List[CommittableMessage[F]]] =
          Stream.exec(logger.info(s"Starting connector for [$source]")) ++
            self
              .consumeBatched(source)
              .map {
                _.map {
                  _.mapScope {
                    _.flatTap { payload =>
                      /* Note: this logs *after* the resource has been acquired (which is the only place where we'll see a message, so it
                       * makes sense) and *before* the resource is closed.
                       */
                      Resource.eval(logger.debug(s"Received message [$payload] from [$source]")).onFinalizeCase {
                        case ExitCase.Succeeded  => logger.debug(s"Committing message [$payload]")
                        case ExitCase.Canceled   => logger.warn(s"Rolling back message [$payload] because of cancelation")
                        case ExitCase.Errored(e) => logger.error(e)(s"Rolling back message [$payload] because of an error")
                      }
                    }
                  }
                }
              }
              .onFinalizeCase {
                case ExitCase.Succeeded  => logger.info(s"Stopping connector for [$source] normally")
                case ExitCase.Canceled   => logger.warn(s"Stopping connector for [$source] because of cancelation")
                case ExitCase.Errored(e) => logger.error(e)(s"Stopping connector for [$source] due to an error")
              }

        override def produce[R >: P](message: Message[R]): F[Unit] =
          logger.trace(s"Sending message to destination [${message.destination}]: [${message.payload}]") *> self.produce(message)

      }

  }

}
