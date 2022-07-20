package com.ocadotechnology.pass4s.addons.s3proxy

import cats.effect.IO
import cats.effect.Resource
import cats.effect.std.UUIDGen
import com.ocadotechnology.pass4s.connectors.sns.Sns
import com.ocadotechnology.pass4s.connectors.sns.SnsConnector
import com.ocadotechnology.pass4s.connectors.sns.SnsDestination
import com.ocadotechnology.pass4s.connectors.sns.SnsFifo
import com.ocadotechnology.pass4s.connectors.sqs.Sqs
import com.ocadotechnology.pass4s.connectors.sqs.SqsConnector
import com.ocadotechnology.pass4s.connectors.sqs.SqsEndpoint
import com.ocadotechnology.pass4s.connectors.sqs.SqsFifo
import com.ocadotechnology.pass4s.core.Message
import com.ocadotechnology.pass4s.high.Broker
import com.ocadotechnology.pass4s.kernel.Consumer
import com.ocadotechnology.pass4s.s3proxy.S3Client
import com.ocadotechnology.pass4s.s3proxy.S3ProxyConfig
import com.ocadotechnology.pass4s.s3proxy.syntax._
import com.ocadotechnology.pass4s.util.LocalStackContainerUtils._
import org.testcontainers.containers.localstack.LocalStackContainer.Service
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.slf4j.Slf4jLogger
import weaver.MutableIOSuite

import scala.concurrent.duration.DurationInt
import scala.concurrent.duration.FiniteDuration

object S3ProxyTests extends MutableIOSuite {

  override type Res =
    (Broker[IO, Sns with SnsFifo with Sqs with SqsFifo], S3Client[IO], SnsConnector.SnsConnector[IO], SqsConnector.SqsConnector[IO])

  implicit val logger: Logger[IO] = Slf4jLogger.getLogger

  override def sharedResource: Resource[
    IO,
    (Broker[IO, Sns with SnsFifo with Sqs with SqsFifo], S3Client[IO], SnsConnector.SnsConnector[IO], SqsConnector.SqsConnector[IO])
  ] =
    for {
      container    <- containerResource(Seq(Service.SNS, Service.SQS, Service.S3))
      s3Client     <- createS3Client(container)
      snsConnector <- createSnsConnector(container)
      sqsConnector <- createSqsConnector(container)
      broker       <- Resource.eval(Broker.mergeByCapabilities(Broker.fromConnector(snsConnector), Broker.fromConnector(sqsConnector)))
    } yield (broker, s3Client, snsConnector, sqsConnector)

  def bucketAndTopics(s3Client: S3Client[IO], snsConnector: SnsConnector.SnsConnector[IO], sqsConnector: SqsConnector.SqsConnector[IO]) =
    for {
      bucketName     <- Resource.eval(UUIDGen[IO].randomUUID.map(_.toString()))
      _              <- s3BucketResource(s3Client)(bucketName)
      (topic, queue) <- topicWithSubscriptionResource(snsConnector.underlying, sqsConnector.underlying)("output-topic")
    } yield (bucketName, topic, queue)

  test("Sender and consumer should do a full S3 round trip, message should not be deleted afterwards when deleting is disabled").usingRes {
    case (broker, implicit0(s3Client: S3Client[IO]), snsConnector, sqsConnector) =>
      bucketAndTopics(s3Client, snsConnector, sqsConnector).use { case (bucketName, topicArn, queueUrl) =>
        val senderConfig =
          S3ProxyConfig
            .Sender
            .withSnsDefaults(bucketName)
            .copy(
              minPayloadSize = Some(1)
            )
        val consumerConfig =
          S3ProxyConfig
            .Consumer
            .withSnsDefaults()
            .copy(
              shouldDeleteAfterProcessing = false
            )
        val payload = Message.Payload("body", Map("foo" -> "bar"))
        val consumer: Consumer[IO, Message.Payload] =
          broker.consumer(SqsEndpoint(queueUrl)).usingS3Proxy(consumerConfig)
        val consume1MessageFromQueue =
          Consumer.toStreamSynchronous(consumer).head.compile.lastOrError
        val sendMessageOnTopic =
          broker.sender.usingS3Proxy(senderConfig).sendOne(Message(payload, SnsDestination(topicArn)).widen)

        for {
          _            <- sendMessageOnTopic
          objects      <- s3Client.listObjects(bucketName)
          message      <- consume1MessageFromQueue
          afterObjects <- s3Client.listObjects(bucketName)
        } yield expect.all(
          message == payload,
          objects.size == 1,
          afterObjects.size == 1
        )
      }
  }

  test("Sender and consumer should do a full S3 round trip, message should be deleted afterwards when deleting is enabled").usingRes {
    case (broker, implicit0(s3Client: S3Client[IO]), snsConnector, sqsConnector) =>
      bucketAndTopics(s3Client, snsConnector, sqsConnector).use { case (bucketName, topicArn, queueUrl) =>
        val senderConfig =
          S3ProxyConfig
            .Sender
            .withSnsDefaults(bucketName)
            .copy(
              minPayloadSize = Some(1)
            )
        val consumerConfig =
          S3ProxyConfig
            .Consumer
            .withSnsDefaults()
            .copy(
              shouldDeleteAfterProcessing = true
            )
        val payload = Message.Payload("body", Map("foo" -> "bar"))
        val consumer: Consumer[IO, Message.Payload] =
          broker.consumer(SqsEndpoint(queueUrl, SqsEndpoint.Settings(cancelableMessageProcessing = false))).usingS3Proxy(consumerConfig)
        val consume1MessageFromQueue =
          Consumer.toStreamSynchronous(consumer).head.compile.lastOrError
        val sendMessageOnTopic =
          broker.sender.usingS3Proxy(senderConfig).sendOne(Message(payload, SnsDestination(topicArn)).widen)
        for {
          _            <- sendMessageOnTopic
          objects      <- s3Client.listObjects(bucketName)
          message      <- consume1MessageFromQueue
          afterObjects <- waitUntil(s3Client.listObjects(bucketName))(_.isEmpty)(200.millis, 20)
        } yield expect.all(
          message == payload,
          objects.size == 1,
          afterObjects.isEmpty
        )
      }
  }

  test("Round trip should not send to s3 when message is too small").usingRes {
    case (broker, implicit0(s3Client: S3Client[IO]), snsConnector, sqsConnector) =>
      bucketAndTopics(s3Client, snsConnector, sqsConnector).use { case (bucketName, topicArn, queueUrl) =>
        val senderConfig =
          S3ProxyConfig
            .Sender
            .withSnsDefaults(bucketName)
            .copy(
              minPayloadSize = Some(Int.MaxValue)
            )
        val consumerConfig =
          S3ProxyConfig
            .Consumer
            .withSnsDefaults()
        val payload = Message.Payload("body", Map("foo" -> "bar"))
        val consumer = broker.consumer(SqsEndpoint(queueUrl)).usingS3Proxy(consumerConfig)
        val consume1MessageFromQueue =
          Consumer.toStreamSynchronous(consumer).head.compile.lastOrError
        val sender = broker.sender.usingS3Proxy(senderConfig)
        val sendMessageOnTopic = sender.sendOne(Message(payload, SnsDestination(topicArn)).widen)

        for {
          _       <- sendMessageOnTopic
          objects <- s3Client.listObjects(bucketName)
          message <- consume1MessageFromQueue
        } yield expect.all(
          message == payload,
          objects.isEmpty
        )
      }

  }

  test("When large enough message is sent, it lands on S3").usingRes {
    case (broker, implicit0(s3Client: S3Client[IO]), snsConnector, sqsConnector) =>
      bucketAndTopics(s3Client, snsConnector, sqsConnector).use { case (bucketName, topicArn, _) =>
        val senderConfig =
          S3ProxyConfig
            .Sender
            .withSnsDefaults(bucketName)
            .copy(
              minPayloadSize = Some(0)
            )
        val payload = Message.Payload("body", Map("foo" -> "bar"))
        val sender = broker.sender.usingS3Proxy(senderConfig)
        val sendMessageOnTopic = sender.sendOne(Message(payload, SnsDestination(topicArn)).widen)

        for {
          initial <- s3Client.listObjects(bucketName)
          _       <- sendMessageOnTopic
          objects <- s3Client.listObjects(bucketName)
        } yield expect.all(
          initial.isEmpty,
          objects.nonEmpty
        )
      }
  }

  test("Objects are persisted when consumer fails to process them").usingRes {
    case (broker, implicit0(s3Client: S3Client[IO]), snsConnector, sqsConnector) =>
      bucketAndTopics(s3Client, snsConnector, sqsConnector).use { case (bucketName, topicArn, queueUrl) =>
        val senderConfig =
          S3ProxyConfig
            .Sender
            .withSnsDefaults(bucketName)
            .copy(
              minPayloadSize = Some(0)
            )
        val consumerConfig =
          S3ProxyConfig
            .Consumer
            .withSnsDefaults()
            .copy(
              shouldDeleteAfterProcessing = true
            )
        val payload = Message.Payload("body", Map("foo" -> "bar"))
        val sender = broker.sender.usingS3Proxy(senderConfig)
        val sendMessageOnTopic = sender.sendOne(Message(payload, SnsDestination(topicArn)).widen)
        val consumer = broker.consumer(SqsEndpoint(queueUrl)).usingS3Proxy(consumerConfig)

        for {
          _       <- consumer.consume(_ => IO.raiseError(new RuntimeException("intentionally failed"))).background.use_
          initial <- s3Client.listObjects(bucketName)
          _       <- sendMessageOnTopic
          objects <- s3Client.listObjects(bucketName)
        } yield expect.all(
          initial.isEmpty,
          objects.nonEmpty
        )
      }
  }

  def waitUntil[A](action: => IO[A])(predicate: A => Boolean)(sleepDuration: FiniteDuration, attempts: Int): IO[A] = {
    assert(attempts > 0, "Attempts must be positive")
    fs2
      .Stream
      .range[IO, Int](0, attempts)
      .evalMap(_ => action)
      .metered(sleepDuration)
      .dropWhile(result => !predicate(result))
      .compile
      .lastOrError
  }

}
