package com.ocado.ospnow.wms.pass4s.connectors.activemq

import akka.actor.ActorSystem
import akka.stream.{RestartSettings => AkkaRestartSettings}
import cats.effect.Resource
import com.ocado.ospnow.wms.pass4s.connectors.activemq.JmsSource.JmsSourceSettings
import com.ocado.ospnow.wms.pass4s.connectors.activemq.consumer._
import com.ocado.ospnow.wms.pass4s.connectors.activemq.producer._
import com.ocado.ospnow.wms.pass4s.core.CommittableMessage
import com.ocado.ospnow.wms.pass4s.core.Connector
import com.ocado.ospnow.wms.pass4s.core.Destination
import com.ocado.ospnow.wms.pass4s.core.Message
import com.ocado.ospnow.wms.pass4s.core.Source
import fs2.Stream
import org.typelevel.log4cats.Logger

import javax.jms.ConnectionFactory
import scala.concurrent.duration._
import scala.reflect.runtime.universe._
import cats.effect.kernel.Async

trait Jms

object Jms {
  sealed trait Type extends Product with Serializable

  object Type {
    final case object Queue extends Type
    final case object Topic extends Type
  }

}

final case class JmsSource private (name: String, sourceType: Jms.Type, settings: JmsSourceSettings) extends Source[Jms] {
  override val capability: Type = typeOf[Jms]

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

final case class JmsDestination private (name: String, destinationType: Jms.Type) extends Destination[Jms] {
  override val capability: Type = typeOf[Jms]

  def toSource(settings: JmsSourceSettings = JmsSourceSettings()): JmsSource = JmsSource(name, destinationType, settings)
}

object JmsDestination {
  def queue(name: String): JmsDestination = JmsDestination(name, Jms.Type.Queue)

  def topic(name: String): JmsDestination = JmsDestination(name, Jms.Type.Topic)
}

object JmsConnector {
  type JmsConnector[F[_]] = Connector.Aux[F, Jms, ConnectionFactory]

  //these might have to return resources,
  //we might also have variants that build an Egress directly or have a conversion method on Connector
  //(probably not, as methods on Connector shouldn't be used by end users)
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
