package com.ocadotechnology.pass4s.connectors.jms

import cats.effect.IO
import cats.effect.Resource
import cats.implicits._
import com.ocadotechnology.pass4s.connectors.activemq.Jms
import com.ocadotechnology.pass4s.connectors.activemq.JmsDestination
import com.ocadotechnology.pass4s.connectors.activemq.JmsSource
import com.ocadotechnology.pass4s.connectors.activemq.JmsSource.JmsSourceSettings
import com.ocadotechnology.pass4s.connectors.util.EmbeddedJmsBroker._
import com.ocadotechnology.pass4s.core.Message
import com.ocadotechnology.pass4s.high.Broker
import com.ocadotechnology.pass4s.kernel.Consumer
import fs2.concurrent.SignallingRef
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.slf4j.Slf4jLogger
import weaver.MutableIOSuite

import scala.concurrent.duration._
import scala.util.Random

object JmsTests extends MutableIOSuite {

  override type Res = Broker[IO, Jms]

  private implicit val logger: Logger[IO] = Slf4jLogger.getLogger

  override def sharedResource: Resource[IO, Broker[IO, Jms]] = createBrokerAndConnectToIt.map(Broker.fromConnector[IO, Jms])

  private val randomDestinationName: IO[String] = IO(Random.alphanumeric.take(8).mkString).map(randomSuffix => s"test-$randomSuffix")

  private val somePayload: Message.Payload = Message.Payload("body", Map())
  private val somePayloadWithAttributes: Message.Payload = Message.Payload("body", Map("foo1" -> "bar1", "foo2" -> "bar2"))

  test("consumer should receive message on Queue, sender should send message on Queue") { broker =>
    randomDestinationName.flatMap { queueName =>
      val consume1MessageFromQueue =
        Consumer.toStreamBounded(maxSize = 1)(broker.consumer(JmsSource.queue(queueName))).head.compile.lastOrError
      val sendMessageOnQueue = broker.sender.sendOne(Message(somePayloadWithAttributes, JmsDestination.queue(queueName)))

      // no need to wait for the consumer to be up and ready (Queues are persistent).
      (consume1MessageFromQueue <& sendMessageOnQueue).map { payload =>
        expect(payload == somePayloadWithAttributes)
      }
    }
  }

  test("consumer should receive message on Topic, sender should send message on Topic") { broker =>
    randomDestinationName.flatMap { topicName =>
      val consume1MessageFromTopic =
        Consumer.toStreamBounded(maxSize = 1)(broker.consumer(JmsSource.topic(topicName))).head.compile.lastOrError
      val sendMessageOnTopic = broker.sender.sendOne(Message(somePayloadWithAttributes, JmsDestination.topic(topicName)))

      // add delay before sending to ensure that the consumer is up and ready (Topics are not persistent).
      (consume1MessageFromTopic <& (IO.sleep(200.millis) *> sendMessageOnTopic)).map { payload =>
        expect(payload == somePayloadWithAttributes)
      }
    }
  }

  test("consumer should receive messages in parallel if parallelSessions > 1") { broker =>
    randomDestinationName.product(SignallingRef(0)).flatMap {
      case (queueName, signallingRef) =>
        val incrementingQueueConsumer = broker.consumer(JmsSource.queue(queueName, JmsSourceSettings(parallelSessions = 2))).consume { _ =>
          signallingRef.update(_ + 1) *> IO.sleep(500.millis) *> signallingRef.update(_ - 1)
        }
        val listenOnChangesInSignallingRefUntilAllConsumed = signallingRef.discrete.drop(1).takeThrough(_ != 0).compile.toList
        val sendMessageOnQueue = broker.sender.sendOne(Message(somePayload, JmsDestination.queue(queueName)))

        incrementingQueueConsumer
          .background
          .surround(listenOnChangesInSignallingRefUntilAllConsumed <& (IO.sleep(200.millis) *> sendMessageOnQueue *> sendMessageOnQueue))
          .map(changes => expect(changes == List(1, 2, 1, 0)))
    }
  }

  test("when consumer is failing message should be rollbacked") { broker =>
    randomDestinationName.flatMap { queueName =>
      val failingQueueConsumer = broker.consumer(JmsSource.queue(queueName)).consume(_ => IO.raiseError(new Exception("processing failed")))
      val consume1MessageFromDlq =
        Consumer.toStreamBounded(maxSize = 1)(broker.consumer(JmsSource.queue(s"ActiveMQ.DLQ.Queue.$queueName"))).head.compile.lastOrError
      val sendMessageOnQueue = broker.sender.sendOne(Message(somePayload, JmsDestination.queue(queueName)))

      failingQueueConsumer
        .background
        .surround(consume1MessageFromDlq <& sendMessageOnQueue)
        .map(dlqMessages => expect(dlqMessages.text == somePayload.text))
    }
  }

  test("when consumer is processing message for too long, then processing should be interrupted and message should be rollbacked") {
    broker =>
      randomDestinationName.flatMap { queueName =>
        val longProcessingQueueConsumer = broker
          .consumer(JmsSource.queue(queueName, JmsSourceSettings(messageProcessingTimeout = 1.second)))
          .consume(_ => IO.sleep(10.seconds))
        val consume1MessageFromDlq =
          Consumer.toStreamBounded(maxSize = 1)(broker.consumer(JmsSource.queue(s"ActiveMQ.DLQ.Queue.$queueName"))).head.compile.lastOrError
        val sendMessageOnQueue = broker.sender.sendOne(Message(somePayload, JmsDestination.queue(queueName)))

        longProcessingQueueConsumer
          .background
          .surround(consume1MessageFromDlq <& sendMessageOnQueue)
          .map(dlqMessages => expect(dlqMessages.text == somePayload.text))
      }
  }

}
