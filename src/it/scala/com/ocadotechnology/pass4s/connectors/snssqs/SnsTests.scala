package com.ocadotechnology.pass4s.connectors.snssqs

import cats.effect.IO
import cats.effect.Resource
import cats.implicits._
import com.ocadotechnology.pass4s.connectors.sns.Sns
import com.ocadotechnology.pass4s.connectors.sns.SnsArn
import com.ocadotechnology.pass4s.connectors.sns.SnsClientException
import com.ocadotechnology.pass4s.connectors.sns.SnsDestination
import com.ocadotechnology.pass4s.connectors.sns.SnsFifo
import com.ocadotechnology.pass4s.connectors.sns.SnsFifoDestination
import com.ocadotechnology.pass4s.connectors.sqs.Sqs
import com.ocadotechnology.pass4s.connectors.sqs.SqsEndpoint
import com.ocadotechnology.pass4s.connectors.sqs.SqsFifo
import com.ocadotechnology.pass4s.connectors.sqs.SqsFifoEndpoint
import com.ocadotechnology.pass4s.core.Message
import com.ocadotechnology.pass4s.core.groupId.MessageGroup
import com.ocadotechnology.pass4s.high.Broker
import com.ocadotechnology.pass4s.kernel.Consumer
import com.ocadotechnology.pass4s.kernel.Sender
import com.ocadotechnology.pass4s.util.LocalStackContainerUtils._
import io.circe.Encoder
import io.circe.Json
import io.circe.syntax.EncoderOps
import io.laserdisc.pure.sns.tagless.SnsAsyncClientOp
import io.laserdisc.pure.sqs.tagless.SqsAsyncClientOp
import org.testcontainers.containers.localstack.LocalStackContainer.Service
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.slf4j.Slf4jLogger
import weaver.MutableIOSuite

import scala.annotation.nowarn

@nowarn
object SnsTests extends MutableIOSuite {
  override type Res = (Broker[IO, Sns with SnsFifo with Sqs with SqsFifo], SnsAsyncClientOp[IO], SqsAsyncClientOp[IO])

  implicit val logger: Logger[IO] = Slf4jLogger.getLogger

  override def sharedResource
    : Resource[IO, (Broker[IO, Sns with SnsFifo with Sqs with SqsFifo], SnsAsyncClientOp[IO], SqsAsyncClientOp[IO])] =
    for {
      container    <- containerResource(Seq(Service.SNS, Service.SQS))
      snsConnector <- createSnsConnector(container)
      sqsConnector <- createSqsConnector(container)
      broker       <- Resource.eval(Broker.mergeByCapabilities(Broker.fromConnector(snsConnector), Broker.fromConnector(sqsConnector)))
    } yield (broker, snsConnector.underlying, sqsConnector.underlying)

  test("sending a message should publish it").usingRes { case (broker, snsClient, sqsClient) =>
    val payload = Message.Payload("body", Map("foo" -> "bar"))
    topicWithSubscriptionResource(snsClient, sqsClient)("output-topic")
      .use { case (topicArn, queueUrl) =>
        val consume1MessageFromQueue =
          Consumer.toStreamBounded(maxSize = 1)(broker.consumer(SqsEndpoint(queueUrl))).head.compile.lastOrError
        val sendMessageOnTopic = broker.sender.sendOne(Message(payload, SnsDestination(topicArn)))

        consume1MessageFromQueue <& sendMessageOnTopic
      }
      .map(message => expect(message == payload))
  }

  test("sending a message through fifo topic should preserve the ordering within a message group").usingRes {
    case (broker, snsClient, sqsClient) =>
      val numMessages = 10L
      val payloads =
        0L.until(numMessages).map(n => Message.Payload(s"body$n", Map(SnsFifo.groupIdMetadata -> (n % 2).toString, "foo" -> "bar"))).toList

      topicWithSubscriptionResource(snsClient, sqsClient)("fifo-topic", isFifo = true)
        .use { case (topicArn, queueUrl) =>
          val consume10MessagesFromQueue =
            Consumer.toStreamBounded(maxSize = 1)(broker.consumer(SqsFifoEndpoint(queueUrl))).take(numMessages).compile.toList
          val sendMessagesOnTopic = broker.sender[SnsFifo].sendAll(payloads.map(Message(_, SnsFifoDestination(topicArn))))

          consume10MessagesFromQueue <& sendMessagesOnTopic
        }
        .map { messages =>
          val messagesByGroupId = messages.groupBy(_.metadata(SnsFifo.groupIdMetadata))
          val expectedByGroupId = payloads.groupBy(_.metadata(SnsFifo.groupIdMetadata))
          expect(messagesByGroupId == expectedByGroupId)
        } *> ignore("This test is flaky and mostly tests the AWS behavior")
  }

  test("sending a message through using a convenient sender works properly").usingRes { case (broker, snsClient, sqsClient) =>
    final case class Foo(bar: Int, order: String)
    object Foo {
      implicit val encoder: Encoder[Foo] = foo => Json.obj("bar" -> Json.fromInt(foo.bar), "order" -> Json.fromString(foo.order))
      implicit val messageGroup: MessageGroup[Foo] = _.order
    }

    val payload = Foo(2137, order = "some-order")

    topicWithSubscriptionResource(snsClient, sqsClient)("fifo-topic", isFifo = true)
      .use { case (topicArn, queueUrl) =>
        import com.ocadotechnology.pass4s.circe.syntax._
        val sender: Sender[IO, Foo] = broker.sender.asJsonSenderWithMessageGroup[Foo](SnsFifoDestination(topicArn))
        val consumeMessageFromQueue =
          Consumer
            .toStreamBounded(maxSize = 1)(broker.consumer(SqsFifoEndpoint(queueUrl)))
            .head
            .compile
            .lastOrError

        consumeMessageFromQueue <& sender.sendOne(payload)
      }
      .map { receivedPayload =>
        expect.all(
          receivedPayload.text == payload.asJson.noSpaces,
          receivedPayload.metadata == Map(SnsFifo.groupIdMetadata -> payload.order)
        )
      }
  }

  test("exception should be wrapped using own exception").usingRes { case (broker, _, _) =>
    val payload = Message.Payload("body", Map())
    broker.sender.sendOne(Message(payload, SnsDestination(SnsArn("nonexistent-topic")))).attempt.map { res =>
      expect(res.leftMap(_.getClass) == Left(classOf[SnsClientException]))
    }
  }

  test("ensure at compile time that message groups are provided") {
    IO {
      import org.scalatest.matchers.should.Matchers._

      """
        import com.ocadotechnology.pass4s.circe.syntax._
        import com.ocadotechnology.pass4s.core.Destination

        final case class Foo(bar: Int, order: String)
        object Foo {
          implicit val encoder: Encoder[Foo] = io.circe.generic.semiauto.deriveEncoder[Foo]
          // it does not matter whether or not the instance below is provided;
          // the only thing we're really checking is the destination type
          implicit val messageGroup: MessageGroup[Foo] = _.order
        }

        val rootSender: Sender[IO, Message[SnsFifo with Sns]] = ???

        // this is the where the magic happens -- you should not be able to use `.asJsonSender` with `Destination[SnsFifo]`
        val dest: Destination[SnsFifo] = ???
        rootSender.asJsonSender[Foo](dest)
      """ shouldNot compile

      expect(true)
    }
  }

}
