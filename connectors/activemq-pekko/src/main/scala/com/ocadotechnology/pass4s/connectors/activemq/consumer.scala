/*
 * Copyright 2024 Ocado Technology
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

package com.ocadotechnology.pass4s.connectors.pekko.activemq

import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.stream.connectors.jms.scaladsl.JmsConsumer
import org.apache.pekko.stream.connectors.{jms => pekkojms}
import org.apache.pekko.stream.scaladsl.RestartSource
import cats.ApplicativeThrow
import cats.effect.Async
import cats.effect.Sync
import cats.implicits._
import com.ocadotechnology.pass4s.connectors.pekko.activemq.taps._
import com.ocadotechnology.pass4s.core.Message.Payload
import com.ocadotechnology.pass4s.core.CommittableMessage
import com.ocadotechnology.pass4s.core.Source
import fs2.Stream
import org.typelevel.log4cats.Logger

import javax.jms
import scala.jdk.CollectionConverters._
import scala.concurrent.duration._
import scala.util.Try

private[activemq] object consumer {

  def consumeAndReconnectOnErrors[F[_]: Async: Logger](
    connectionFactory: jms.ConnectionFactory
  )(
    source: Source[_]
  )(
    implicit as: ActorSystem
  ): Stream[F, CommittableMessage[F]] =
    for {
      JmsSource(name, sourceType, settings) <- Stream.eval(extractJmsSource[F](source))

      jmsConsumerSettings = pekkojms
                              .JmsConsumerSettings(as, connectionFactory)
                              .withAckTimeout((settings.messageProcessingTimeout + 1.second) * 1.2)
                              .withSessionCount(settings.parallelSessions)
                              .withFailStreamOnAckTimeout(true)
                              .withDestination(common.toPekkoDestination(name, sourceType))

      txEnvelope         <- RestartSource
                              .withBackoff(settings.restartSettings.toAkka) { () =>
                                JmsConsumer.txSource(jmsConsumerSettings).named(getClass.getSimpleName)
                              }
                              .toStream[F]()
      committableMessage <- Stream.eval(toCommittableMessage(txEnvelope)).unNone
    } yield committableMessage

  private def extractJmsSource[F[_]: ApplicativeThrow](source: Source[_]): F[JmsSource] =
    source match {
      case jmsSource: JmsSource   => jmsSource.pure[F]
      case unsupportedDestination =>
        ApplicativeThrow[F].raiseError(
          new UnsupportedOperationException(s"JmsConnector does not support destination: $unsupportedDestination")
        )
    }

  private def toCommittableMessage[F[_]: Sync: Logger](txEnvelope: pekkojms.TxEnvelope): F[Option[CommittableMessage[F]]] = {
    val commit = Sync[F].delay(txEnvelope.commit())
    val rollback = Sync[F].delay(txEnvelope.rollback())
    txEnvelope.message match {
      case textMessage: jms.TextMessage =>
        CommittableMessage.instance(Payload(textMessage.getText, getHeaders(textMessage)), commit, _ => rollback).some.pure[F]
      case unsupportedMessage           =>
        Logger[F].warn(s"JmsConnector supports only TextMessages. Ignoring received message: $unsupportedMessage") *> rollback.as(None)
    }
  }

  // fixme: add headers/properties from underlying message - need to double check if all properties are returned by getPropertyNames
  private def getHeaders(msg: jms.Message): Map[String, String] =
    Try {
      msg
        .getPropertyNames
        .asIterator()
        .asInstanceOf[java.util.Iterator[String]] // Please forgive me I have to do this, but underneath it is really String
        .asScala
        .map(name => name -> msg.getStringProperty(name))
        .toMap
    }.getOrElse(Map.empty)

}
