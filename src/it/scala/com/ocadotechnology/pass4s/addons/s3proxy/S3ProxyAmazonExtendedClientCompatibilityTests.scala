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
import cats.implicits.*
import com.amazon.sqs.javamessaging.*
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
import software.amazon.awssdk.services.s3.S3Client as AwsS3Client
import software.amazon.awssdk.services.sqs.SqsClient as AwsSqsClient
import software.amazon.awssdk.services.sqs.model.MessageAttributeValue
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest
import software.amazon.awssdk.services.sqs.model.SendMessageRequest
import weaver.MutableIOSuite

import scala.jdk.CollectionConverters.*

object S3ProxyAmazonExtendedClientCompatibilityTests extends MutableIOSuite {

  case class Environment(
    broker: Broker[IO, Sqs],
    s3Client: S3Client[IO],
    sqsConnector: SqsConnector.SqsConnector[IO],
    awsSqsClient: AwsSqsClient,
    awsS3Client: AwsS3Client
  )

  override type Res = Environment

  implicit val logger: Logger[IO] = Slf4jLogger.getLogger

  override def sharedResource: Resource[IO, Res] =
    for {
      container    <- containerResource(Seq(Service.SQS, Service.S3))
      s3Client     <- createS3Client(container)
      sqsConnector <- createSqsConnector(container)

      awsSqsClient = AwsSqsClient
                       .builder
                       .endpointOverride(container.endpointOverride(Service.SQS))
                       .region(container.region)
                       .credentialsProvider(container.staticCredentialsProvider)
                       .build
      awsS3Client = AwsS3Client
                      .builder
                      .endpointOverride(container.endpointOverride(Service.S3))
                      .region(container.region)
                      .credentialsProvider(container.staticCredentialsProvider)
                      .build

    } yield Environment(Broker.fromConnector(sqsConnector), s3Client, sqsConnector, awsSqsClient, awsS3Client)

  def bucketAndTopics(env: Environment): Resource[IO, (AmazonSQSExtendedClient, String, SqsUrl)] =
    for {
      bucketName <- s3BucketResource(env.s3Client)("bucket")
      queue      <- queueResource(env.sqsConnector.underlying)("queue")
      amazonSqsExtendedClient = {
        val config = new ExtendedClientConfiguration()
          .withPayloadSupportEnabled(env.awsS3Client, bucketName, false)
          .withAlwaysThroughS3(true)
          .withLegacyReservedAttributeNameDisabled()
        new AmazonSQSExtendedClient(env.awsSqsClient, config)
      }
    } yield (amazonSqsExtendedClient, bucketName, queue)

  val payload: Message.Payload = Message.Payload("body", Map("foo" -> "bar"))

  test("message sent from Amazon SQS Extended Client can be handled by Consumer using S3Proxy addon") { env =>
    bucketAndTopics(env).use { case (amazonSqsExtendedClient, bucketName, queueUrl) =>
      implicit val s3Client: S3Client[IO] = env.s3Client

      val amazonClientSendMessage = {
        val metadata = payload.metadata.fmap(MessageAttributeValue.builder().dataType("String").stringValue(_).build()).asJava
        val request = SendMessageRequest.builder().queueUrl(queueUrl.value).messageBody(payload.text).messageAttributes(metadata).build()
        IO.delay(amazonSqsExtendedClient.sendMessage(request))
      }

      val pass4sConsume = {
        val config = S3ProxyConfig.Consumer.withSnsDefaults().copy(shouldDeleteAfterProcessing = false)
        val consumer = env.broker.consumer(SqsEndpoint(queueUrl)).usingS3ProxyForBigPayload(config)
        Consumer.toStreamSynchronous(consumer).head.compile.lastOrError
      }

      for {
        _               <- amazonClientSendMessage
        s3Objects       <- s3Client.listObjects(bucketName)
        storedPayload   <- s3Objects.headOption.flatTraverse(s3Client.getObject(bucketName, _))
        receivedPayload <- pass4sConsume
      } yield expect.all(
        receivedPayload == payload,
        storedPayload.contains(payload.text)
      )
    }
  }

  test("message sent from Sender using S3Proxy addon can be handled by Amazon SQS Extended Client") { env =>
    bucketAndTopics(env).use { case (amazonSqsExtendedClient, bucketName, queueUrl) =>
      implicit val s3Client: S3Client[IO] = env.s3Client

      val pass4sSendMessage = {
        val config = S3ProxyConfig.Sender.withSnsDefaults(bucketName).copy(minPayloadSize = None)
        env.broker.sender.usingS3ProxyForBigPayload(config).sendOne(Message(payload, SqsDestination(queueUrl)))
      }

      val amazonClientReceiveMessage = {
        val request = ReceiveMessageRequest.builder().queueUrl(queueUrl.value).messageAttributeNames("All").build()
        IO.delay(amazonSqsExtendedClient.receiveMessage(request).messages().asScala.headOption)
      }

      val attributeValue = MessageAttributeValue.builder().dataType("String").stringValue("bar").build()

      for {
        _               <- pass4sSendMessage
        s3Objects       <- s3Client.listObjects(bucketName)
        storedPayload   <- s3Objects.headOption.flatTraverse(s3Client.getObject(bucketName, _))
        receivedPayload <- amazonClientReceiveMessage.map(_.get)
      } yield expect.all(
        receivedPayload.body() == payload.text,
        receivedPayload.messageAttributes().asScala == Map(
          "foo" -> attributeValue
        ),
        storedPayload.contains(payload.text)
      )
    }
  }

}
