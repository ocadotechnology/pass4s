package com.ocadotechnology.pass4s.connectors.jms

import akka.actor.ActorSystem
import akka.stream.alpakka.jms.ConnectionRetryException
import cats.effect.IO
import cats.effect.Resource
import cats.effect.kernel.Deferred
import cats.effect.std.Semaphore
import cats.implicits._
import com.ocadotechnology.pass4s.connectors.activemq.Jms
import com.ocadotechnology.pass4s.connectors.activemq.JmsDestination
import com.ocadotechnology.pass4s.connectors.activemq.JmsSource
import com.ocadotechnology.pass4s.core.Message
import com.ocadotechnology.pass4s.high.Broker
import com.ocadotechnology.pass4s.kernel.Consumer
import com.ocadotechnology.pass4s.util.EmbeddedJmsBroker._
import com.ocadotechnology.pass4s.util.ResourceAccess
import org.apache.activemq.broker.BrokerService
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.slf4j.Slf4jLogger
import weaver.SimpleIOSuite

import java.net.ServerSocket
import scala.util.Random

object JmsRecoveryTests extends SimpleIOSuite {

  private implicit val logger: Logger[IO] = Slf4jLogger.getLogger

  def testEnvironment: Resource[
    IO,
    (
      Broker[IO, Jms],
      Resource[IO, BrokerService]
    )
  ] =
    for {
      implicit0(as: ActorSystem) <- actorSystemResource
      brokerName                 <- Resource.eval(IO(Random.alphanumeric.take(8).mkString).map(randomSuffix => s"broker-$randomSuffix"))
      brokerUrl <- Resource.eval(Resource.fromAutoCloseable(IO(new ServerSocket(0))).use(p => IO(s"tcp://localhost:${p.getLocalPort}")))

      brokerService = brokerServiceResource(brokerName, brokerUrl.some)
      connector <- createJmsConnector(brokerUrl)
    } yield (Broker.fromConnector(connector), brokerService)

  test(
    "when the broker goes down while a message is being processed, then after startup message and further messages should be processed"
  ) {
    (testEnvironment, Resource.eval((Deferred[IO, Unit], Semaphore[IO](0)).tupled)).tupled.use {
      case ((broker, brokerService), (messageProcessingReleaser, startOfMessageProcessingHolder)) =>
        val mainQueue = "MainQueue"
        val feedbackQueue = "FeedbackQueue"
        def sendMessageOnQueue(
          queueName: String,
          text: String
        ): IO[Unit] =
          broker.sender.sendOne(Message(Message.Payload(text, Map()), JmsDestination.queue(queueName)))

        val controlledConsumerMainQueue = broker.consumer(JmsSource.queue(mainQueue)).consume { message =>
          startOfMessageProcessingHolder.release *> // bookmark #1
            messageProcessingReleaser.get *> // bookmark #2
            sendMessageOnQueue(feedbackQueue, message.text.replace("A", "B"))
        }
        val consume2MessagesFromFeedbackQueue =
          Consumer.toStreamBounded(maxSize = 1)(broker.consumer(JmsSource.queue(feedbackQueue))).take(2).compile.toList

        (ResourceAccess.fromResource(brokerService) <* controlledConsumerMainQueue.background).use { brokerService =>
          sendMessageOnQueue(mainQueue, "A1") *>
            startOfMessageProcessingHolder.acquire *> // wait for bookmark #1
            brokerService.shutdown *>
            messageProcessingReleaser.complete(()) *> // release bookmark #2
            brokerService.start *>
            sendMessageOnQueue(mainQueue, "A2") *>
            consume2MessagesFromFeedbackQueue
              .map(messagesFromFeedbackQueue => expect(messagesFromFeedbackQueue.map(_.text) == List("B1", "B2")))
        }
    }
  }

  test("when the broker goes down while a message is being sent, then sender should throw exception") {
    testEnvironment.use { case (broker, brokerService) =>
      val testQueue = "TestQueue"
      def sendMessageOnQueue(
        text: String,
        queueName: String = testQueue
      ): IO[Unit] =
        broker.sender.sendOne(Message(Message.Payload(text, Map()), JmsDestination.queue(queueName)))

      val consume2MessagesFromQueue =
        Consumer.toStreamBounded(maxSize = 1)(broker.consumer(JmsSource.queue(testQueue))).take(2).compile.toList

      ResourceAccess.fromResource(brokerService).use { brokerService =>
        (
          consume2MessagesFromQueue,
          for {
            a1Result <- sendMessageOnQueue("A1").attempt
            a2Result <- sendMessageOnQueue("A2", queueName = "").attempt // empty destination name => exception
            _        <- brokerService.shutdown
            a3Result <- sendMessageOnQueue("A3").attempt // producing while broker is down => exception
            a4Result <- sendMessageOnQueue("A4").attempt
            _        <- brokerService.start
            a5Result <- sendMessageOnQueue("A5").attempt
          } yield (a1Result, a2Result, a3Result, a4Result, a5Result)
        ).parMapN { case (messagesFromQueue, (a1Result, a2Result, a3Result, a4Result, a5Result)) =>
          expect.all(
            a1Result == Right(()),
            a2Result.leftMap(_.getMessage) == Left("Invalid destination name: a non-empty name is required"),
            a3Result.leftMap(_.isInstanceOf[ConnectionRetryException]) == Left(true),
            a4Result.leftMap(_.isInstanceOf[ConnectionRetryException]) == Left(true),
            a5Result == Right(()),
            messagesFromQueue.map(_.text) == List("A1", "A5")
          )
        }
      }
    }
  }

}
