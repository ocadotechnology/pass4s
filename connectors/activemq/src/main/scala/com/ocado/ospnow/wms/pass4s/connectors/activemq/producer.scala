package com.ocadotechnology.pass4s.connectors.activemq

import akka.actor.ActorSystem
import akka.stream.alpakka.jms.scaladsl.JmsProducer
import akka.stream.alpakka.{jms => alpakka}
import cats.ApplicativeThrow
import cats.effect.Concurrent
import cats.effect.Resource
import cats.effect.implicits._
import cats.effect.kernel.Async
import cats.effect.kernel.Deferred
import cats.effect.kernel.Ref
import cats.effect.std.Queue
import cats.effect.std.Semaphore
import cats.implicits._
import com.ocadotechnology.pass4s.connectors.activemq.taps._
import com.ocadotechnology.pass4s.core.Destination
import com.ocadotechnology.pass4s.core.Message
import fs2.Pipe
import fs2.Stream

import javax.jms

private[activemq] object producer {

  type MessageProducer[F[_]] = Message[_] => F[Unit]

  private type Attempt = Either[Throwable, Unit]
  private type Promise[F[_]] = Deferred[F, Attempt]
  private type JmsPayload[F[_]] = alpakka.JmsEnvelope[Promise[F]]


  def createMessageProducer[F[_]: Async](
    connectionFactory: jms.ConnectionFactory,
    bufferSize: Int = 100
  )(
    implicit as: ActorSystem
  ): Resource[F, MessageProducer[F]] =
    for {
      queue <- Resource.eval(Queue.bounded[F, JmsPayload[F]](bufferSize))
      /**
        * Stream.eval(queue.take) wouldn't work here because it takes only single element and terminates.
        * In this case we need to take all elements but one by one as long as there's anything in the queue.
        * Limit is set to one as we only process single message at a time, so that we don't reemit chukns in case of failure.
        */
      _     <- Stream.fromQueueUnterminated(queue, limit = 1).through(sendMessageAndCompletePromise(connectionFactory)).compile.drain.background
    } yield enqueueAndWaitForPromise[F](queue.offer)

  private def enqueueAndWaitForPromise[F[_]: Concurrent](enqueue: JmsPayload[F] => F[Unit]): MessageProducer[F] =
    message =>
      for {
        jmsDestination <- extractJmsDestination[F](message.destination)
        promise        <- Deferred[F, Attempt]
        alpakkaMessage = alpakka.JmsTextMessage(message.payload.text, promise).withProperties(message.payload.metadata)
        alpakkaDestination = common.toAlpakkaDestination(jmsDestination.name, jmsDestination.destinationType)
        _              <- enqueue(alpakkaMessage.to(alpakkaDestination))
        _              <- promise.get.rethrow
      } yield ()

  private def sendMessageAndCompletePromise[F[_]: Async](
    connectionFactory: jms.ConnectionFactory
  )(
    implicit as: ActorSystem
  ): Pipe[F, JmsPayload[F], Unit] = { messages =>
    /*
     * Note on `inflightMessages` Ref:
     * Every message is added to Ref at the beginning of the message processing:
     *  - (happy path) after the message is sent the promise of this message is completed and the message is removed from Ref
     *  - (edge case) when `sendMessagePipe` crashes all inflightMessages are completed with Left and the Ref is cleaned
     *
     * Note on semaphore:
     * Every operation on `inflightMessages` Ref is guarded by single permit uncancelable semaphore to guarantee
     * that the promise completion and Ref update are atomic
     */
    Stream.eval((Ref.of[F, Set[JmsPayload[F]]](Set.empty), Semaphore[F](n = 1)).tupled).flatMap {
      case (inflightMessages, semaphore) =>
        val jmsProducerSettings = alpakka
          .JmsProducerSettings(as, connectionFactory)
          .withTopic("Pass4s.Default") // default destination is obligatory, but always overridden

        val sendMessagePipe: Pipe[F, JmsPayload[F], JmsPayload[F]] =
          JmsProducer.flexiFlow[Promise[F]](jmsProducerSettings).named(getClass.getSimpleName).toPipe[F]()

        def addMessageToRef(pendingMessage: JmsPayload[F]) =
          semaphore.permit.surround(inflightMessages.update(_ + pendingMessage))

        def completeMessageAndRemoveFromRef(sentMessage: JmsPayload[F]) =
          semaphore.permit.surround(sentMessage.passThrough.complete(Right(())).attempt *> inflightMessages.update(_ - sentMessage))

        def failAllAndCleanRef(ex: Throwable) =
          semaphore
            .permit
            .surround(
              inflightMessages.get.flatMap(_.toList.traverse(_.passThrough.complete(Left(ex)).attempt)) *> inflightMessages.set(Set())
            )

        messages
          .evalTap(addMessageToRef)
          .through(sendMessagePipe)
          .attempts(Stream.constant(jmsProducerSettings.connectionRetrySettings.initialRetry))
          .evalMap(_.fold(failAllAndCleanRef, completeMessageAndRemoveFromRef))
    }
  }

  private def extractJmsDestination[F[_]: ApplicativeThrow](destination: Destination[_]): F[JmsDestination] =
    destination match {
      case jmsDestination: JmsDestination => jmsDestination.pure[F]
      case unsupportedDestination         =>
        ApplicativeThrow[F].raiseError(
          new UnsupportedOperationException(s"JmsConnector does not support destination: $unsupportedDestination")
        )
    }

}
