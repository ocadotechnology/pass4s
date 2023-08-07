package com.ocadotechnology.pass4s.util

import cats.Endo
import cats.effect.IO
import cats.effect.Resource
import cats.implicits._
import com.dimafeng.testcontainers.LocalStackV2Container
import com.ocadotechnology.pass4s.connectors.kinesis.KinesisConnector
import com.ocadotechnology.pass4s.connectors.kinesis.KinesisConnector.KinesisConnector
import com.ocadotechnology.pass4s.connectors.sns.SnsArn
import com.ocadotechnology.pass4s.connectors.sns.SnsConnector
import com.ocadotechnology.pass4s.connectors.sns.SnsConnector.SnsConnector
import com.ocadotechnology.pass4s.connectors.sqs.SqsConnector
import com.ocadotechnology.pass4s.connectors.sqs.SqsUrl
import com.ocadotechnology.pass4s.connectors.sqs.SqsConnector.SqsConnector
import com.ocadotechnology.pass4s.s3proxy.S3Client
import io.laserdisc.pure.kinesis.tagless.KinesisAsyncClientOp
import io.laserdisc.pure.sns.tagless.SnsAsyncClientOp
import io.laserdisc.pure.sqs.tagless.SqsAsyncClientOp
import org.testcontainers.containers.localstack.LocalStackContainer.Service
import org.typelevel.log4cats.Logger
import software.amazon.awssdk.services.kinesis.model.CreateStreamRequest
import software.amazon.awssdk.services.kinesis.model.DeleteStreamRequest
import software.amazon.awssdk.services.kinesis.model.DescribeStreamRequest
import software.amazon.awssdk.services.sns.model.CreateTopicRequest
import software.amazon.awssdk.services.sns.model.DeleteTopicRequest
import software.amazon.awssdk.services.sns.model.SubscribeRequest
import software.amazon.awssdk.services.sqs.model.CreateQueueRequest
import software.amazon.awssdk.services.sqs.model.DeleteQueueRequest
import software.amazon.awssdk.services.sqs.model.QueueAttributeName

import scala.jdk.FutureConverters._
import scala.jdk.CollectionConverters._
import scala.util.Random

object LocalStackContainerUtils {

  private def createContainer(services: Seq[LocalStackV2Container.Service]): IO[LocalStackV2Container] =
    IO {
      val localStackTag = "0.14.5"
      LocalStackV2Container(tag = localStackTag, services = services)
        .configure(_.setDockerImageName(s"localstack/localstack:$localStackTag"))
    }

  def containerResource(services: Seq[LocalStackV2Container.Service]): Resource[IO, LocalStackV2Container] =
    TestContainersUtils.containerResource(createContainer(services))

  def createKinesisConnector(
    container: LocalStackV2Container
  ): Resource[IO, KinesisConnector[IO]] =
    KinesisConnector.usingLocalAwsWithDefaultAttributesProvider(
      container.endpointOverride(Service.KINESIS),
      container.region,
      container.staticCredentialsProvider
    )

  def createSnsConnector(
    container: LocalStackV2Container
  ): Resource[IO, SnsConnector[IO]] =
    SnsConnector.usingLocalAwsWithDefaultAttributesProvider(
      container.endpointOverride(Service.SNS),
      container.region,
      container.staticCredentialsProvider
    )

  def createSqsConnector(
    container: LocalStackV2Container
  )(
    implicit logger: Logger[IO]
  ): Resource[IO, SqsConnector[IO]] =
    SqsConnector.usingLocalAwsWithDefaultAttributesProvider(
      container.endpointOverride(Service.SQS),
      container.region,
      container.staticCredentialsProvider
    )

  def createS3Client(
    container: LocalStackV2Container
  ): Resource[IO, S3Client[IO]] =
    S3Client.usingLocalAws(
      container.endpointOverride(Service.S3),
      container.region,
      container.staticCredentialsProvider
    )

  private val fifoExtraAttributes =
    Map(
      QueueAttributeName.DEDUPLICATION_SCOPE -> "messageGroup",
      QueueAttributeName.CONTENT_BASED_DEDUPLICATION -> true.toString,
      QueueAttributeName.FIFO_THROUGHPUT_LIMIT -> "perMessageGroupId"
    )

  def queueResource(
    sqsClient: SqsAsyncClientOp[IO]
  )(
    queueName: String,
    additionalParameters: Endo[CreateQueueRequest.Builder] = identity,
    isFifo: Boolean = false
  ): Resource[IO, SqsUrl] =
    Resource
      .make(for {
        randomSuffix <- IO(Random.alphanumeric.take(8).mkString)
        fifoSuffix = if (isFifo) ".fifo" else ""
        attrs = Map(QueueAttributeName.FIFO_QUEUE -> isFifo.toString) ++ (if (isFifo) fifoExtraAttributes else Map.empty)
        response     <- sqsClient.createQueue(
                          additionalParameters(
                            CreateQueueRequest.builder().queueName(s"$queueName-$randomSuffix$fifoSuffix").attributes(attrs.asJava)
                          ).build()
                        )
      } yield response.queueUrl())(queueUrl => sqsClient.deleteQueue(DeleteQueueRequest.builder().queueUrl(queueUrl).build()).void)
      .map(SqsUrl)

  def topicResource(
    snsClient: SnsAsyncClientOp[IO]
  )(
    topicName: String,
    additionalParameters: Endo[CreateTopicRequest.Builder] = identity,
    isFifo: Boolean = false
  ): Resource[IO, SnsArn] =
    Resource
      .make(for {
        randomSuffix <- IO(Random.alphanumeric.take(8).mkString)
        fifoSuffix = if (isFifo) ".fifo" else ""
        attrs = Map("FifoTopic" -> isFifo.toString) ++ (if (isFifo) Map("ContentBasedDeduplication" -> true.toString) else Map.empty)
        response     <-
          snsClient.createTopic(
            additionalParameters(CreateTopicRequest.builder().name(s"$topicName-$randomSuffix$fifoSuffix").attributes(attrs.asJava)).build()
          )
      } yield response.topicArn())(topicArn => snsClient.deleteTopic(DeleteTopicRequest.builder().topicArn(topicArn).build()).void)
      .map(SnsArn)

  def topicWithSubscriptionResource(
    snsClient: SnsAsyncClientOp[IO],
    sqsClient: SqsAsyncClientOp[IO]
  )(
    topicName: String,
    isFifo: Boolean = false
  ): Resource[IO, (SnsArn, SqsUrl)] =
    for {
      topicArn <- topicResource(snsClient)(topicName, isFifo = isFifo)
      queueUrl <- queueResource(sqsClient)(s"$topicName-sub", isFifo = isFifo)
      subscribeRequest = SubscribeRequest
                           .builder()
                           .topicArn(topicArn.value)
                           .protocol("sqs")
                           .endpoint(queueUrl.value)
                           .attributes(Map("RawMessageDelivery" -> "true").asJava)
                           .build()
      _        <- Resource.eval(snsClient.subscribe(subscribeRequest))
    } yield (topicArn, queueUrl)

  def kinesisStreamResource(
    kinesisClient: KinesisAsyncClientOp[IO]
  )(
    streamName: String,
    additionalParameters: Endo[CreateStreamRequest.Builder] = identity
  ): Resource[IO, String] =
    Resource.make(for {
      sn <- IO(Random.alphanumeric.take(8).mkString).map(randomSuffix => s"$streamName-$randomSuffix")
      _  <- kinesisClient.createStream(
              additionalParameters(CreateStreamRequest.builder().streamName(sn).shardCount(1)).build()
            )
      _  <- kinesisClient.waiter.flatMap { waiter =>
              val describeStreamRequest = DescribeStreamRequest.builder().streamName(sn).build()
              IO.fromFuture(IO(waiter.waitUntilStreamExists(describeStreamRequest).asScala))
            }
    } yield sn)(sn => kinesisClient.deleteStream(DeleteStreamRequest.builder().streamName(sn).build()).void)

  def s3BucketResource(
    s3Client: S3Client[IO]
  )(
    bucketNamePrefix: String
  ): Resource[IO, String] =
    Resource.make {
      IO(Random.alphanumeric.take(8).mkString.toLowerCase)
        .map(randomSuffix => s"$bucketNamePrefix-$randomSuffix")
        .flatTap(s3Client.createBucket)
    } { bucketName =>
      for {
        leftovers <- s3Client.listObjects(bucketName)
        _         <- leftovers.traverse(key => s3Client.deleteObject(bucketName, key))
        _         <- s3Client.deleteBucket(bucketName)
      } yield ()
    }

}
