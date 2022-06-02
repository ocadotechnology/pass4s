package com.ocado.ospnow.wms.pass4s.high

import cats.ApplicativeThrow
import cats.MonadThrow
import cats.effect.Resource
import cats.effect.implicits._
import cats.effect.kernel.Async
import cats.implicits._
import com.ocado.ospnow.wms.pass4s.core.Connector
import com.ocado.ospnow.wms.pass4s.core.Destination
import com.ocado.ospnow.wms.pass4s.core.End
import com.ocado.ospnow.wms.pass4s.core.Message
import com.ocado.ospnow.wms.pass4s.core.Message.Payload
import com.ocado.ospnow.wms.pass4s.core.Source
import com.ocado.ospnow.wms.pass4s.kernel._
import fs2.Stream

import scala.reflect.runtime.universe._

trait Broker[F[_], +P] {
  def consumer[R >: P](source: Source[R]): Consumer[F, Payload]

  def sender[R >: P]: Sender[F, Message[R]]
}

object Broker {

  def apply[F[_], P](implicit ev: Broker[F, P]): ev.type = ev

  def fromConnector[F[_]: Async, P](connector: Connector[F, P]): Broker[F, P] =
    new Broker[F, P] {

      override def consumer[R >: P](source: Source[R]): Consumer[F, Payload] = {
        val toConsumer: Stream[F, List[Resource[F, Payload]]] => Consumer[F, Payload] = {
          if (source.maxConcurrent === 1) s => Consumer.sequential(source.cancelableMessageProcessing)(s.flatMap(Stream.emits))
          else s => Consumer.paralleled(source.maxConcurrent, source.cancelableMessageProcessing)(s.flatMap(Stream.emits))
          // TODO: if we ever figure out how to properly do batched FIFO with retries, add a case here
        }

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

  def mergeByCapabilities[F[_]: ApplicativeThrow, P1: TypeTag, P2: TypeTag](
    broker1: Broker[F, P1],
    broker2: Broker[F, P2]
  ): F[Broker[F, P1 with P2]] = {
    val commonCapabilities = (typeOf[P1].baseClasses intersect typeOf[P2].baseClasses)
      .filterNot(List(typeOf[Object], typeOf[Any]).map(_.typeSymbol).contains)
    if (commonCapabilities.nonEmpty)
      ApplicativeThrow[F].raiseError(
        new IllegalArgumentException(
          s"While merging, brokers can not have common capability. Broker 1: ${typeOf[P1]}, Broker 2: ${typeOf[P2]}, Common: $commonCapabilities"
        )
      )
    else
      new Broker[F, P1 with P2] {
        override def consumer[R >: P1 with P2](source: Source[R]): Consumer[F, Payload] =
          source.capability match {
            case p1 if typeOf[P1] <:< p1 => broker1.asInstanceOf[Broker[F, R]].consumer(source)
            case p2 if typeOf[P2] <:< p2 => broker2.asInstanceOf[Broker[F, R]].consumer(source)
            case _                       =>
              throw new UnsupportedOperationException(
                s"Broker with capabilities [${typeOf[P1]} with ${typeOf[P2]}] doesn't support capability ${source.capability} from $source"
              )
          }

        override def sender[R >: P1 with P2]: Sender[F, Message[R]] =
          Sender.routed { message =>
            message.destination.capability match {
              case p1 if typeOf[P1] <:< p1 => broker1.asInstanceOf[Broker[F, R]].sender
              case p2 if typeOf[P2] <:< p2 => broker2.asInstanceOf[Broker[F, R]].sender
              case _                       =>
                throw new UnsupportedOperationException(
                  s"Broker with capabilities [${typeOf[P1]} with ${typeOf[P2]}] doesn't support capability ${message.destination.capability} from $message"
                )
            }
          }
      }.pure[F]
  }

  // boring stuff

  def mergeByCapabilities[F[_]: MonadThrow, P1: TypeTag, P2: TypeTag, P3: TypeTag](
    broker1: Broker[F, P1],
    broker2: Broker[F, P2],
    broker3: Broker[F, P3]
  ): F[Broker[F, P1 with P2 with P3]] =
    mergeByCapabilities[F, P1, P2](broker1, broker2)
      .flatMap(mergeByCapabilities[F, P1 with P2, P3](_, broker3))

  def mergeByCapabilities[F[_]: MonadThrow, P1: TypeTag, P2: TypeTag, P3: TypeTag, P4: TypeTag](
    broker1: Broker[F, P1],
    broker2: Broker[F, P2],
    broker3: Broker[F, P3],
    broker4: Broker[F, P4]
  ): F[Broker[F, P1 with P2 with P3 with P4]] =
    mergeByCapabilities[F, P1, P2, P3](broker1, broker2, broker3)
      .flatMap(mergeByCapabilities[F, P1 with P2 with P3, P4](_, broker4))

  def mergeByCapabilities[F[_]: MonadThrow, P1: TypeTag, P2: TypeTag, P3: TypeTag, P4: TypeTag, P5: TypeTag](
    broker1: Broker[F, P1],
    broker2: Broker[F, P2],
    broker3: Broker[F, P3],
    broker4: Broker[F, P4],
    broker5: Broker[F, P5]
  ): F[Broker[F, P1 with P2 with P3 with P4 with P5]] =
    mergeByCapabilities[F, P1, P2, P3, P4](broker1, broker2, broker3, broker4)
      .flatMap(mergeByCapabilities[F, P1 with P2 with P3 with P4, P5](_, broker5))

}
