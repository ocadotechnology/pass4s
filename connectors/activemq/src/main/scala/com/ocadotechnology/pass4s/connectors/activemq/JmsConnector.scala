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

package com.ocadotechnology.pass4s.connectors.activemq

import akka.actor.ActorSystem
import akka.stream.RestartSettings as AkkaRestartSettings
import cats.effect.Resource
import com.ocadotechnology.pass4s.connectors.activemq.JmsSource.JmsSourceSettings
import com.ocadotechnology.pass4s.connectors.activemq.consumer.*
import com.ocadotechnology.pass4s.connectors.activemq.producer.*
import com.ocadotechnology.pass4s.core.CommittableMessage
import com.ocadotechnology.pass4s.core.Connector
import com.ocadotechnology.pass4s.core.Destination
import com.ocadotechnology.pass4s.core.Message
import com.ocadotechnology.pass4s.core.Source
import fs2.Stream
import izumi.reflect.macrortti.LightTypeTag
import org.typelevel.log4cats.Logger

import javax.jms.ConnectionFactory
import scala.concurrent.duration.*
import cats.effect.kernel.Async
import izumi.reflect.Tag

trait Jms

object Jms {
  sealed trait Type extends Product with Serializable

  object Type {
    case object Queue extends Type
    case object Topic extends Type
  }

}

final case class JmsSource private[activemq] (name: String, sourceType: Jms.Type, settings: JmsSourceSettings)
  extends Source[Jms] {
  override val capability: LightTypeTag = Tag[Jms].tag

  override val messageProcessingTimeout: Option[FiniteDuration] = Some(settings.messageProcessingTimeout)
  override val cancelableMessageProcessing: Boolean = settings.cancelableMessageProcessing
  override val maxConcurrent: Int = settings.parallelSessions

  def toDestination: JmsDestination = JmsDestination(name, sourceType)
}

object JmsSource {

  final case class JmsSourceSettings(
    // sets internal timeout on a message processing. JMS' ackTimeout will be (x + 1 second) * 1.2
    messageProcessingTimeout: FiniteDuration = 30.seconds,
    cancelableMessageProcessing: Boolean = true,
    parallelSessions: Int = 1,
    restartSettings: RestartSettings = RestartSettings(minBackoff = 2.second, maxBackoff = 30.seconds, randomFactor = 0.2)
  )

  final case class RestartSettings(minBackoff: FiniteDuration, maxBackoff: FiniteDuration, randomFactor: Double) {
    val toAkka: AkkaRestartSettings = AkkaRestartSettings(minBackoff, maxBackoff, randomFactor)
  }

  def queue(name: String, settings: JmsSourceSettings = JmsSourceSettings()): JmsSource = JmsSource(name, Jms.Type.Queue, settings)

  def topic(name: String, settings: JmsSourceSettings = JmsSourceSettings()): JmsSource = JmsSource(name, Jms.Type.Topic, settings)
}

final case class JmsDestination(name: String, destinationType: Jms.Type) extends Destination[Jms] {
  override val capability: LightTypeTag = Tag[Jms].tag

  def toSource(settings: JmsSourceSettings = JmsSourceSettings()): JmsSource = JmsSource(name, destinationType, settings)
}

object JmsDestination {
  def queue(name: String): JmsDestination = JmsDestination(name, Jms.Type.Queue)

  def topic(name: String): JmsDestination = JmsDestination(name, Jms.Type.Topic)
}

object JmsConnector {
  type JmsConnector[F[_]] = Connector.Aux[F, Jms, ConnectionFactory]

  // these might have to return resources,
  // we might also have variants that build an Egress directly or have a conversion method on Connector
  // (probably not, as methods on Connector shouldn't be used by end users)
  def singleBroker[F[_]: Logger: Async](
    username: String,
    password: String,
    url: String
  )(
    implicit as: ActorSystem
  ): Resource[F, JmsConnector[F]] =
    ConnectionFactories.pooled(username, password, url).flatMap(singleBroker[F](_))

  def singleBroker[F[_]: Logger: Async](
    connectionFactory: ConnectionFactory
  )(
    implicit as: ActorSystem
  ): Resource[F, JmsConnector[F]] =
    for {
      producer <- createMessageProducer(connectionFactory)
    } yield new Connector[F, Jms] {

      type Raw = ConnectionFactory
      override val underlying: ConnectionFactory = connectionFactory

      override def consumeBatched[R >: Jms](source: Source[R]): Stream[F, List[CommittableMessage[F]]] =
        consumeAndReconnectOnErrors(connectionFactory)(source).map(List(_))

      override def produce[R >: Jms](message: Message[R]): F[Unit] =
        producer(message)

    }

}
