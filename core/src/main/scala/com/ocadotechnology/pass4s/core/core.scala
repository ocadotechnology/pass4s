/*
 * Copyright 2022 Ocado Technology
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

package com.ocadotechnology.pass4s.core

import cats.Applicative
import cats.effect.Resource
import cats.effect.kernel.Resource.ExitCase
import com.ocadotechnology.pass4s.core.Message.Payload
import fs2.Stream

import scala.concurrent.duration.FiniteDuration
import scala.reflect.runtime.universe.Type

final case class Message[P](
  payload: Message.Payload,
  destination: Destination[P]
) {
  def widen[R <: P]: Message[R] = this.asInstanceOf[Message[R]]
}

object Message {

  def addHeader[P](key: String, value: String): Message[P] => Message[P] =
    msg => msg.copy(payload = Payload.addHeader(key, value)(msg.payload))

  final case class Payload(
    text: String,
    // might needs splitting into headers/properties - or use this as a generic representation,
    // and use something like prefixes (prop_FOO, header_FOO) to avoid being technology-specific in this model.
    metadata: Map[String, String]
  )

  object Payload {
    def addHeader(key: String, value: String): Payload => Payload = p => p.copy(metadata = p.metadata + (key -> value))
    def getHeader(key: String): Payload => Option[String] = _.metadata.get(key)

  }

}

trait End[P] {
  def name: String
  def capability: Type
}

trait Source[P] extends End[P] {
  def messageProcessingTimeout: Option[FiniteDuration] = None
  def cancelableMessageProcessing: Boolean = true
  def maxConcurrent: Int = 1
}

trait Destination[P] extends End[P]

trait CommittableMessage[F[_]] { self =>
  def scope: Resource[F, Payload]

  def mapScope(f: Resource[F, Payload] => Resource[F, Payload]): CommittableMessage[F] =
    new CommittableMessage[F] {
      val scope: Resource[F, Payload] = f(self.scope)
    }

}

object CommittableMessage {

  def instance[F[_]: Applicative](
    payload: Payload,
    commit: F[Unit],
    rollback: RollbackCause => F[Unit]
  ): CommittableMessage[F] =
    just(payload).mapScope {
      _.onFinalizeCase {
        RollbackCause.fromExitCase(_).fold(rollback, _ => commit)
      }
    }

  def just[F[_]](payload: Payload): CommittableMessage[F] =
    new CommittableMessage[F] {
      val scope: Resource[F, Payload] = Resource.pure(payload)
    }

}

sealed trait RollbackCause extends Product with Serializable {

  def fold[A](thrown: Throwable => A, canceled: => A): A =
    this match {
      case RollbackCause.Thrown(e) => thrown(e)
      case RollbackCause.Canceled  => canceled
    }

}

object RollbackCause {
  final case class Thrown(e: Throwable) extends RollbackCause
  case object Canceled extends RollbackCause

  val fromExitCase: ExitCase => Either[RollbackCause, Unit] = {
    case ExitCase.Succeeded  => Right(())
    case ExitCase.Errored(e) => Left(RollbackCause.Thrown(e))
    case ExitCase.Canceled   => Left(RollbackCause.Canceled)
  }

}

trait Connector[F[_], P] {
  type Raw
  def underlying: Raw

  def consumeBatched[R >: P](source: Source[R]): Stream[F, List[CommittableMessage[F]]]

  def consume[R >: P](source: Source[R]): Stream[F, CommittableMessage[F]] = consumeBatched(source).flatMap(Stream.emits)

  def produce[R >: P](message: Message[R]): F[Unit]
}

object Connector {
  type Aux[F[_], P, Raw_] = Connector[F, P] { type Raw = Raw_ }
}
