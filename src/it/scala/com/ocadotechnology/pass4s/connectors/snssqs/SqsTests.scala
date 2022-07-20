package com.ocadotechnology.pass4s.connectors.snssqs

import cats.effect.IO
import cats.effect.Resource
import cats.implicits._
import com.ocadotechnology.pass4s.connectors.sqs.Sqs
import com.ocadotechnology.pass4s.connectors.sqs.SqsClientException
import com.ocadotechnology.pass4s.connectors.sqs.SqsDestination
import com.ocadotechnology.pass4s.connectors.sqs.SqsEndpoint
import com.ocadotechnology.pass4s.connectors.sqs.SqsFifo
import com.ocadotechnology.pass4s.connectors.sqs.SqsFifoEndpoint
import com.ocadotechnology.pass4s.connectors.sqs.SqsUrl
import com.ocadotechnology.pass4s.core.Message
import com.ocadotechnology.pass4s.high.Broker
import com.ocadotechnology.pass4s.kernel.Consumer
import com.ocadotechnology.pass4s.util.LocalStackContainerUtils._
import fs2.concurrent.SignallingRef
import io.laserdisc.pure.sqs.tagless.SqsAsyncClientOp
import org.testcontainers.containers.localstack.LocalStackContainer.Service
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.slf4j.Slf4jLogger
import software.amazon.awssdk.services.sqs.model.MessageAttributeValue
import software.amazon.awssdk.services.sqs.model.QueueAttributeName
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest
import software.amazon.awssdk.services.sqs.model.SendMessageRequest
import weaver.MutableIOSuite

import scala.concurrent.duration._
import scala.jdk.CollectionConverters._

object SqsTests extends MutableIOSuite {
  override type Res = (Broker[IO, Sqs with SqsFifo], SqsAsyncClientOp[IO])

  implicit val logger: Logger[IO] = Slf4jLogger.getLogger

  override def sharedResource: Resource[IO, (Broker[IO, Sqs with SqsFifo], SqsAsyncClientOp[IO])] =
    for {
      container    <- containerResource(Seq(Service.SQS))
      sqsConnector <- createSqsConnector(container)
    } yield (Broker.fromConnector(sqsConnector), sqsConnector.underlying)

  test("consumer should receive message").usingRes { case (broker, client) =>
    queueResource(client)("input-queue")
      .use { queueUrl =>
        val sendMessageRequest = SendMessageRequest
          .builder()
          .queueUrl(queueUrl.value)
          .messageBody("foo")
          .messageAttributes(messageAttributes("foo" -> "bar").asJava)
          .build()

        val consume1MessageFromQueue =
          Consumer.toStreamBounded(maxSize = 1)(broker.consumer(SqsEndpoint(queueUrl))).head.compile.lastOrError
        val sendMessageOnQueue = client.sendMessage(sendMessageRequest)
        val readCurrentQueueMessages = client.receiveMessage(ReceiveMessageRequest.builder().queueUrl(queueUrl.value).build())

        (consume1MessageFromQueue <& sendMessageOnQueue).product(readCurrentQueueMessages)
      }
      .map { case (message, receiveMessageResponse) =>
        expect.all(message == Message.Payload("foo", Map("foo" -> "bar")), receiveMessageResponse.messages().isEmpty)
      }
  }

  // NOTE: the process failure semantics are dictated by SQS itself:
  //  > When you receive a message with a message group ID, no more messages for the same message group ID are returned unless you delete
  //  > the message or it becomes visible.
  //  src: https://docs.aws.amazon.com/AWSSimpleQueueService/latest/SQSDeveloperGuide/FIFO-queues-understanding-logic.html
  test("consumer should process fifo messages in order (within message group), when there are no processing errors").usingRes {
    case (broker, client) =>
      val numMessages = 10L
      val bodiesAndGroups = 0L.until(numMessages).map(n => (s"foo$n", (n % 2).toString)).toList

      queueResource(client)("fifo-queue", isFifo = true)
        .use { queueUrl =>
          def sendMessageRequest(body: String, groupId: String) =
            SendMessageRequest
              .builder()
              .queueUrl(queueUrl.value)
              .messageBody(body)
              .messageGroupId(groupId) // this would normally be inserted automatically by SQS sender
              .messageAttributes(messageAttributes(SqsFifo.groupIdMetadata -> groupId, "foo" -> "bar").asJava)
              .build()

          val requests = bodiesAndGroups.map((sendMessageRequest _).tupled)

          val consume10MessagesFromQueue =
            Consumer.toStreamBounded(maxSize = 1)(broker.consumer(SqsFifoEndpoint(queueUrl))).take(numMessages).compile.toList
          val sendMessagesOnQueue = requests.traverse(client.sendMessage)
          val readCurrentQueueMessages = client.receiveMessage(ReceiveMessageRequest.builder().queueUrl(queueUrl.value).build())

          (consume10MessagesFromQueue <& sendMessagesOnQueue).product(readCurrentQueueMessages)
        }
        .map { case (messages, receiveMessageResponse) =>
          expect.all(
            messages.groupMap(_.metadata(SqsFifo.groupIdMetadata))(_.text) == bodiesAndGroups.groupMap(_._2)(_._1),
            receiveMessageResponse.messages().isEmpty
          )
        }
  }

  test(
    "sending two or more of the same message on a FIFO queue with content-based deduplication should result in only one message being " +
      "delivered"
  )
    .usingRes { case (broker, client) =>
      val bodiesAndGroups = List(
        ("foo", "1"),
        ("foo", "1"),
        ("bar", "2"),
        ("bar", "2")
      )

      queueResource(client)("dedup-queue", isFifo = true, isDedup = true)
        .use { queueUrl =>
          def sendMessageRequest(body: String, groupId: String) =
            SendMessageRequest
              .builder()
              .queueUrl(queueUrl.value)
              .messageBody(body)
              .messageGroupId(groupId) // this would normally be inserted automatically by SQS sender
              .messageAttributes(messageAttributes(SqsFifo.groupIdMetadata -> groupId, "foo" -> "bar").asJava)
              .build()

          val requests = bodiesAndGroups.map((sendMessageRequest _).tupled)

          val consumeMessagesFromQueue =
            Consumer.toStreamBounded(maxSize = 1)(broker.consumer(SqsFifoEndpoint(queueUrl))).take(2).compile.toList
          val sendMessagesOnQueue = requests.traverse_(client.sendMessage)
          val readCurrentQueueMessages = client.receiveMessage(ReceiveMessageRequest.builder().queueUrl(queueUrl.value).build())

          (consumeMessagesFromQueue <& sendMessagesOnQueue).product(readCurrentQueueMessages)
        }
        .map { case (messages, receiveMessageResponse) =>
          expect.all(
            messages.groupMap(_.metadata(SqsFifo.groupIdMetadata))(_.text) == bodiesAndGroups.distinct.groupMap(_._2)(_._1),
            receiveMessageResponse.messages().isEmpty
          )
        } *> ignore("This test is flaky and mostly tests the AWS behavior")
    }

  test("consumer should receive messages in parallel if maxConcurrent > 1").usingRes { case (broker, client) =>
    queueResource(client)("input-queue")
      .product(Resource.eval(SignallingRef(0)))
      .use { case (queueUrl, signallingRef) =>
        val incrementingQueueConsumer =
          broker.consumer(SqsEndpoint(queueUrl, SqsEndpoint.Settings(maxConcurrent = 2))).consume { _ =>
            signallingRef.update(_ + 1) *> IO.sleep(500.millis) *> signallingRef.update(_ - 1)
          }
        val listenOnChangesInSignallingRefUntilAllConsumed = signallingRef.discrete.drop(1).takeThrough(_ != 0).compile.toList
        val sendMessageOnQueue = client.sendMessage(SendMessageRequest.builder().queueUrl(queueUrl.value).messageBody("foo").build())

        incrementingQueueConsumer
          .background
          .surround(listenOnChangesInSignallingRefUntilAllConsumed <& sendMessageOnQueue <& sendMessageOnQueue)
      }
      .map(changes => expect(changes == List(1, 2, 1, 0)))
  }

  test("when consumer is failing message should not be deleted").usingRes { case (broker, client) =>
    queueResource(client)("input-dlq")
      .mproduct(dlqUrl => queueResource(client)("input-queue", _.attributes(redrivePolicy(dlqUrl.value).asJava)))
      .use { case (dlqUrl, queueUrl) =>
        val failingQueueConsumer = broker
          .consumer(SqsEndpoint(queueUrl, SqsEndpoint.Settings(messageProcessingTimeout = 1.second)))
          .consume(_ => IO.raiseError(new Exception("processing failed")))
        val consume1MessageFromDlq =
          Consumer.toStreamBounded(maxSize = 1)(broker.consumer(SqsEndpoint(dlqUrl))).head.compile.lastOrError
        val sendMessageOnQueue = client.sendMessage(SendMessageRequest.builder().queueUrl(queueUrl.value).messageBody("foo").build())
        val readCurrentQueueMessages = client.receiveMessage(ReceiveMessageRequest.builder().queueUrl(queueUrl.value).build())

        failingQueueConsumer
          .background
          .surround(consume1MessageFromDlq <& sendMessageOnQueue)
          .product(readCurrentQueueMessages)
      }
      .map { case (message, receiveMessageResponse) =>
        expect.all(message == Message.Payload("foo", Map()), receiveMessageResponse.messages().isEmpty)
      }
  }

  test("when consumer is processing message for too long, then processing should be interrupted and message should not be deleted")
    .usingRes { case (broker, client) =>
      queueResource(client)("input-dlq")
        .mproduct(dlqUrl => queueResource(client)("input-queue", _.attributes(redrivePolicy(dlqUrl.value).asJava)))
        .use { case (dlqUrl, queueUrl) =>
          val longProcessingQueueConsumer = broker
            .consumer(SqsEndpoint(queueUrl, SqsEndpoint.Settings(messageProcessingTimeout = 1.second)))
            .consume(_ => IO.sleep(10.seconds))
          val consume1MessageFromDlq =
            Consumer.toStreamBounded(maxSize = 1)(broker.consumer(SqsEndpoint(dlqUrl))).head.compile.lastOrError
          val sendMessageOnQueue = client.sendMessage(SendMessageRequest.builder().queueUrl(queueUrl.value).messageBody("foo").build())
          val readCurrentQueueMessages = client.receiveMessage(ReceiveMessageRequest.builder().queueUrl(queueUrl.value).build())

          longProcessingQueueConsumer
            .background
            .surround(consume1MessageFromDlq <& sendMessageOnQueue)
            .product(readCurrentQueueMessages)
        }
        .map { case (message, receiveMessageResponse) =>
          expect.all(message == Message.Payload("foo", Map()), receiveMessageResponse.messages().isEmpty)
        }
    }

  test("sending a message should publish it").usingRes { case (broker, client) =>
    val payload = Message.Payload("body", Map("foo" -> "bar"))
    queueResource(client)("input-output-queue")
      .use { queueUrl =>
        val consume1MessageFromQueue =
          Consumer.toStreamBounded(maxSize = 1)(broker.consumer(SqsEndpoint(queueUrl))).head.compile.lastOrError
        val sendMessageOnQueue = broker.sender.sendOne(Message(payload, SqsDestination(queueUrl)))
        val readCurrentQueueMessages = client.receiveMessage(ReceiveMessageRequest.builder().queueUrl(queueUrl.value).build())

        (consume1MessageFromQueue <& sendMessageOnQueue).product(readCurrentQueueMessages)
      }
      .map { case (message, receiveMessageResponse) =>
        expect.all(message == payload, receiveMessageResponse.messages().isEmpty)
      }
  }

  test("exception should be wrapped using own exception").usingRes { case (broker, _) =>
    val payload = Message.Payload("body", Map())
    broker.sender.sendOne(Message(payload, SqsDestination(SqsUrl("nonexistent-queue")))).attempt.map { res =>
      expect(res.leftMap(_.getClass) == Left(classOf[SqsClientException]))
    }
  }

  private val redrivePolicy: String => Map[QueueAttributeName, String] =
    dlqUrl => Map(QueueAttributeName.REDRIVE_POLICY -> s"""{"deadLetterTargetArn":"$dlqUrl","maxReceiveCount":"1"}""")

  private def messageAttributes(args: (String, String)*): Map[String, MessageAttributeValue] =
    args.toMap.view.mapValues(value => MessageAttributeValue.builder().dataType("String").stringValue(value).build()).toMap

}
