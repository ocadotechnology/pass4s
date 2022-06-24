package com.ocadotechnology.pass4s.connectors.kinesis

import cats.effect.IO
import cats.effect.Resource
import cats.implicits._
import com.ocadotechnology.pass4s.connectors.util.LocalStackContainerUtils._
import com.ocadotechnology.pass4s.core.Message
import com.ocadotechnology.pass4s.high.Broker
import io.laserdisc.pure.kinesis.tagless.KinesisAsyncClientOp
import org.testcontainers.containers.localstack.LocalStackContainer.Service
import software.amazon.awssdk.services.kinesis.model.GetRecordsRequest
import software.amazon.awssdk.services.kinesis.model.GetShardIteratorRequest
import software.amazon.awssdk.services.kinesis.model.Record
import software.amazon.awssdk.services.kinesis.model.ShardIteratorType
import weaver.MutableIOSuite

import scala.jdk.CollectionConverters._

object KinesisTests extends MutableIOSuite {
  override type Res = (Broker[IO, Kinesis], KinesisAsyncClientOp[IO])

  override def sharedResource: Resource[IO, (Broker[IO, Kinesis], KinesisAsyncClientOp[IO])] =
    for {
      container        <- containerResource(Seq(Service.KINESIS))
      kinesisConnector <- createKinesisConnector(container)
    } yield (Broker.fromConnector(kinesisConnector), kinesisConnector.underlying)

  test("sending a message should putRecord using random partitionKey").usingRes {
    case (broker, implicit0(kinesisClient: KinesisAsyncClientOp[IO])) =>
      val payload = Message.Payload("body", Map())
      kinesisStreamResource(kinesisClient)("test-stream")
        .use { streamName =>
          val sendMessageOnStream = broker.sender.sendOne(Message(payload, KinesisDestination(streamName)))

          sendMessageOnStream *> getShardIterator(streamName) >>= getRecords
        }
        .map(records =>
          expect.all(
            records.size == 1,
            records.head.data().asUtf8String() == "body",
            records.head.partitionKey().matches("\\w{8}-\\w{4}-\\w{4}-\\w{4}-\\w{12}") // simplified uuid regex
          )
        )
  }

  test("sending a message should putRecord using given partitionKey").usingRes {
    case (broker, implicit0(kinesisClient: KinesisAsyncClientOp[IO])) =>
      val payload = Message.Payload("body", Map(Kinesis.partitionKeyMetadata -> "myPartitionKey"))
      kinesisStreamResource(kinesisClient)("test-stream")
        .use { streamName =>
          val sendMessageOnStream = broker.sender.sendOne(Message(payload, KinesisDestination(streamName)))

          sendMessageOnStream *> getShardIterator(streamName) >>= getRecords
        }
        .map(records =>
          expect.all(
            records.size == 1,
            records.head.data().asUtf8String() == "body",
            records.head.partitionKey() == "myPartitionKey"
          )
        )
  }

  test("exception should be wrapped using own exception").usingRes { case (broker, _) =>
    val payload = Message.Payload("body", Map())
    broker.sender.sendOne(Message(payload, KinesisDestination("nonexistent-stream"))).attempt.map { res =>
      expect(res.leftMap(_.getClass) == Left(classOf[KinesisClientException]))
    }
  }

  private def getShardIterator(streamName: String)(implicit kinesisClient: KinesisAsyncClientOp[IO]): IO[String] =
    kinesisClient
      .getShardIterator(
        GetShardIteratorRequest
          .builder()
          .shardIteratorType(ShardIteratorType.TRIM_HORIZON)
          .streamName(streamName)
          .shardId("shardId-000000000000")
          .build()
      )
      .map(_.shardIterator())

  private def getRecords(shardIterator: String)(implicit kinesisClient: KinesisAsyncClientOp[IO]): IO[List[Record]] =
    kinesisClient.getRecords(GetRecordsRequest.builder().shardIterator(shardIterator).build()).map(_.records().asScala.toList)
}
