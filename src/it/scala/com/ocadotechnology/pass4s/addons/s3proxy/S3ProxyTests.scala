/*
 * Copyright 2023 Ocado Technology
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

package com.ocadotechnology.pass4s.addons.s3proxy

import cats.effect.IO
import cats.effect.Resource
import com.ocadotechnology.pass4s.connectors.sns.*
import com.ocadotechnology.pass4s.connectors.sqs.*
import com.ocadotechnology.pass4s.core.Message
import com.ocadotechnology.pass4s.high.Broker
import com.ocadotechnology.pass4s.kernel.Consumer
import com.ocadotechnology.pass4s.s3proxy.S3Client
import com.ocadotechnology.pass4s.s3proxy.S3ProxyConfig
import com.ocadotechnology.pass4s.s3proxy.syntax.*
import com.ocadotechnology.pass4s.util.LocalStackContainerUtils.*
import org.testcontainers.containers.localstack.LocalStackContainer.Service
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.slf4j.Slf4jLogger
import weaver.MutableIOSuite
import fs2.Stream

import scala.annotation.nowarn
import scala.concurrent.duration.*

@nowarn
object S3ProxyTests extends MutableIOSuite {

  override type Res = (Broker[IO, Sns with Sqs], S3Client[IO], SnsConnector.SnsConnector[IO], SqsConnector.SqsConnector[IO])

  implicit val logger: Logger[IO] = Slf4jLogger.getLogger

  override def sharedResource: Resource[IO, Res] =
    for {
      container    <- containerResource(Seq(Service.SNS, Service.SQS, Service.S3))
      s3Client     <- createS3Client(container)
      snsConnector <- createSnsConnector(container)
      sqsConnector <- createSqsConnector(container)
      broker       <- Resource.eval(Broker.mergeByCapabilities(Broker.fromConnector(snsConnector), Broker.fromConnector(sqsConnector)))
    } yield (broker, s3Client, snsConnector, sqsConnector)

  def bucketAndTopics(s3Client: S3Client[IO], snsConnector: SnsConnector.SnsConnector[IO], sqsConnector: SqsConnector.SqsConnector[IO]) =
    for {
      bucketName     <- s3BucketResource(s3Client)("bucket")
      topicQueuePair <- topicWithSubscriptionResource(snsConnector.underlying, sqsConnector.underlying)("output-topic")
      (topic, queue) = topicQueuePair
    } yield (bucketName, topic, queue)

  val payload: Message.Payload = Message.Payload("body", Map("foo" -> "bar"))

  test("Sender and consumer should do a full S3 round trip. Legacy format.").usingRes {
    case (broker, s3Client, snsConnector, sqsConnector) =>
      implicit val s3ClientImplicit: S3Client[IO] = s3Client
      bucketAndTopics(s3Client, snsConnector, sqsConnector).use { case (bucketName, topicArn, queueUrl) =>
        val consume1MessageFromQueue = {
          val consumerConfig = S3ProxyConfig.Consumer.withSnsDefaults().copy(shouldDeleteAfterProcessing = false)
          val consumer: Consumer[IO, Message.Payload] = broker.consumer(SqsEndpoint(queueUrl)).usingS3ProxyForBigPayload(consumerConfig)
          Consumer.toStreamSynchronous(consumer).head.compile.lastOrError
        }
        val sendMessageOnTopic = {
          val senderConfig = S3ProxyConfig.Sender.withSnsDefaults(bucketName).copy(minPayloadSize = None)
          broker.sender.usingS3ProxyLegacyEncoding(senderConfig).sendOne(Message(payload, SnsDestination(topicArn)).widen)
        }

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

  test("Sender and consumer should do a full S3 round trip, message should not be deleted afterwards when deleting is disabled").usingRes {
    case (broker, s3Client, snsConnector, sqsConnector) =>
      implicit val s3ClientImplicit: S3Client[IO] = s3Client
      bucketAndTopics(s3Client, snsConnector, sqsConnector).use { case (bucketName, topicArn, queueUrl) =>
        val consume1MessageFromQueue = {
          val consumerConfig = S3ProxyConfig.Consumer.withSnsDefaults().copy(shouldDeleteAfterProcessing = false)
          val consumer: Consumer[IO, Message.Payload] = broker.consumer(SqsEndpoint(queueUrl)).usingS3ProxyForBigPayload(consumerConfig)
          Consumer.toStreamSynchronous(consumer).head.compile.lastOrError
        }
        val sendMessageOnTopic = {
          val senderConfig = S3ProxyConfig.Sender.withSnsDefaults(bucketName).copy(minPayloadSize = None)
          broker.sender.usingS3ProxyForBigPayload(senderConfig).sendOne(Message(payload, SnsDestination(topicArn)).widen)
        }
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
    case (broker, s3Client, snsConnector, sqsConnector) =>
      implicit val s3ClientImplicit: S3Client[IO] = s3Client
      bucketAndTopics(s3Client, snsConnector, sqsConnector).use { case (bucketName, topicArn, queueUrl) =>
        val consume1MessageFromQueue = {
          val consumerConfig = S3ProxyConfig.Consumer.withSnsDefaults().copy(shouldDeleteAfterProcessing = true)
          val consumer: Consumer[IO, Message.Payload] =
            broker
              .consumer(SqsEndpoint(queueUrl, SqsEndpoint.Settings(cancelableMessageProcessing = false)))
              .usingS3ProxyForBigPayload(consumerConfig)
          Consumer.toStreamSynchronous(consumer).head.compile.lastOrError
        }

        val sendMessageOnTopic = {
          val senderConfig = S3ProxyConfig.Sender.withSnsDefaults(bucketName).copy(minPayloadSize = Some(1))
          broker.sender.usingS3ProxyForBigPayload(senderConfig).sendOne(Message(payload, SnsDestination(topicArn)).widen)
        }

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

  test("Round trip should not send to s3 when message is too small").usingRes { case (broker, s3Client, snsConnector, sqsConnector) =>
    implicit val s3ClientImplicit: S3Client[IO] = s3Client
    bucketAndTopics(s3Client, snsConnector, sqsConnector).use { case (bucketName, topicArn, queueUrl) =>
      val consume1MessageFromQueue = {
        val consumerConfig = S3ProxyConfig.Consumer.withSnsDefaults()
        val consumer = broker.consumer(SqsEndpoint(queueUrl)).usingS3ProxyForBigPayload(consumerConfig)
        Consumer.toStreamSynchronous(consumer).head.compile.lastOrError
      }

      val sendMessageOnTopic = {
        val senderConfig = S3ProxyConfig.Sender.withSnsDefaults(bucketName).copy(minPayloadSize = Some(Int.MaxValue))
        broker.sender.usingS3ProxyForBigPayload(senderConfig).sendOne(Message(payload, SnsDestination(topicArn)).widen)
      }

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

  test("When large enough message is sent, it lands on S3").usingRes { case (broker, s3Client, snsConnector, sqsConnector) =>
    implicit val s3ClientImplicit: S3Client[IO] = s3Client
    bucketAndTopics(s3Client, snsConnector, sqsConnector).use { case (bucketName, topicArn, _) =>
      val sendMessageOnTopic = {
        val senderConfig = S3ProxyConfig.Sender.withSnsDefaults(bucketName).copy(minPayloadSize = None)
        broker.sender.usingS3ProxyForBigPayload(senderConfig).sendOne(Message(payload, SnsDestination(topicArn)).widen)
      }

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

  test("Objects are persisted when consumer fails to process them").usingRes { case (broker, s3Client, snsConnector, sqsConnector) =>
    implicit val s3ClientImplicit: S3Client[IO] = s3Client
    bucketAndTopics(s3Client, snsConnector, sqsConnector).use { case (bucketName, topicArn, queueUrl) =>
      val sendMessageOnTopic = {
        val senderConfig = S3ProxyConfig.Sender.withSnsDefaults(bucketName).copy(minPayloadSize = Some(0))
        broker.sender.usingS3ProxyForBigPayload(senderConfig).sendOne(Message(payload, SnsDestination(topicArn)).widen)
      }

      val consumer = {
        val consumerConfig = S3ProxyConfig.Consumer.withSnsDefaults().copy(shouldDeleteAfterProcessing = true)
        broker.consumer(SqsEndpoint(queueUrl)).usingS3ProxyForBigPayload(consumerConfig)
      }

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
    Stream
      .range[IO, Int](0, attempts)
      .evalMap(_ => action)
      .metered(sleepDuration)
      .dropWhile(result => !predicate(result))
      .compile
      .lastOrError
  }

}
