package com.ocado.ospnow.wms.pass4s.connectors.activemq

import akka.actor.ActorSystem
import akka.stream.alpakka.jms.scaladsl.JmsConsumer
import akka.stream.alpakka.{jms => alpakka}
import akka.stream.scaladsl.RestartSource
import cats.ApplicativeThrow
import cats.effect.Async
import cats.effect.Sync
import cats.implicits._
import com.ocado.ospnow.wms.pass4s.connectors.activemq.taps._
import com.ocado.ospnow.wms.pass4s.core.Message.Payload
import com.ocado.ospnow.wms.pass4s.core.CommittableMessage
import com.ocado.ospnow.wms.pass4s.core.Source
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

      jmsConsumerSettings = alpakka
                              .JmsConsumerSettings(as, connectionFactory)
                              .withAckTimeout((settings.messageProcessingTimeout + 1.second) * 1.2)
                              .withSessionCount(settings.parallelSessions)
                              .withFailStreamOnAckTimeout(true)
                              .withDestination(common.toAlpakkaDestination(name, sourceType))

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

  private def toCommittableMessage[F[_]: Sync: Logger](txEnvelope: alpakka.TxEnvelope): F[Option[CommittableMessage[F]]] = {
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
