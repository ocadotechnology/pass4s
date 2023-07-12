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

package com.ocadotechnology.pass4s.connectors.kinesis

import cats.ApplicativeThrow
import cats.effect.Async
import cats.effect.Resource
import cats.effect.Sync
import cats.implicits._
import com.ocadotechnology.pass4s.core.Message.Payload
import com.ocadotechnology.pass4s.core._
import fs2.Stream
import io.laserdisc.pure.kinesis.tagless.KinesisAsyncClientOp
import io.laserdisc.pure.kinesis.tagless.{Interpreter => KinesisInterpreter}
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider
import software.amazon.awssdk.core.SdkBytes
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.kinesis.KinesisAsyncClient
import software.amazon.awssdk.services.kinesis.KinesisAsyncClientBuilder
import software.amazon.awssdk.services.kinesis.model.PutRecordRequest

import java.net.URI
import java.util.UUID
import scala.reflect.runtime.universe._

trait Kinesis

object Kinesis {

  /** By default KinesisConnector is using random UUID as a partitionKey. To use specific partitionKey add to the message's metadata entry
    * (Kinesis.partitionKeyMetadata -> "myPartitionKeyValue")
    */
  val partitionKeyMetadata = "pass4s.kinesis.partitionKey"
}

final case class KinesisDestination(name: String) extends Destination[Kinesis] {
  override val capability: Type = typeOf[Kinesis]
}

trait KinesisAttributesProvider[F[_]] {
  def getPartitionKey(payload: Payload, kinesisDestination: KinesisDestination): F[String]
}

object KinesisAttributesProvider {

  def apply[F[_]](
    implicit ev: KinesisAttributesProvider[F]
  ): KinesisAttributesProvider[F] = ev

  def default[F[_]: Sync]: KinesisAttributesProvider[F] =
    (payload: Payload, _: KinesisDestination) =>
      Payload.getHeader(Kinesis.partitionKeyMetadata)(payload) match {
        case Some(partitionKey) => partitionKey.pure[F]
        case None               => Sync[F].delay(UUID.randomUUID().toString)
      }

}

object KinesisConnector {
  type KinesisConnector[F[_]] = Connector.Aux[F, Kinesis, KinesisAsyncClientOp[F]]

  def usingLocalAws[F[_]: Async: KinesisAttributesProvider](
    endpointOverride: URI,
    region: Region,
    credentialsProvider: AwsCredentialsProvider
  ): Resource[F, KinesisConnector[F]] =
    usingBuilder(
      KinesisAsyncClient.builder().endpointOverride(endpointOverride).region(region).credentialsProvider(credentialsProvider)
    )

  def usingLocalAwsWithDefaultAttributesProvider[F[_]: Async](
    endpointOverride: URI,
    region: Region,
    credentialsProvider: AwsCredentialsProvider
  ): Resource[F, KinesisConnector[F]] = {
    implicit val kinesisAttributesProvider: KinesisAttributesProvider[F] = KinesisAttributesProvider.default
    usingLocalAws(endpointOverride, region, credentialsProvider)
  }

  def usingRegion[F[_]: Async: KinesisAttributesProvider](
    region: Region,
    endpointOverride: Option[URI] = None
  ): Resource[F, KinesisConnector[F]] =
    usingBuilder {
      val builder = KinesisAsyncClient.builder().region(region)
      endpointOverride.fold(builder)(builder.endpointOverride)
    }

  def usingRegionWithDefaultAttributesProvider[F[_]: Async](
    region: Region,
    endpointOverride: Option[URI] = None
  ): Resource[F, KinesisConnector[F]] = {
    implicit val kinesisAttributesProvider: KinesisAttributesProvider[F] = KinesisAttributesProvider.default
    usingRegion(region, endpointOverride)
  }

  def usingBuilder[F[_]: Async: KinesisAttributesProvider](
    kinesisBuilder: KinesisAsyncClientBuilder
  ): Resource[F, KinesisConnector[F]] =
    KinesisInterpreter.apply.KinesisAsyncClientOpResource(kinesisBuilder).map(usingPureClient[F](_))

  def usingBuilderWithDefaultAttributesProvider[F[_]: Async](
    kinesisBuilder: KinesisAsyncClientBuilder
  ): Resource[F, KinesisConnector[F]] = {
    implicit val kinesisAttributesProvider: KinesisAttributesProvider[F] = KinesisAttributesProvider.default
    usingBuilder(kinesisBuilder)
  }

  def usingPureClient[F[_]: Sync: KinesisAttributesProvider](kinesisAsyncClientOp: KinesisAsyncClientOp[F]): KinesisConnector[F] =
    new Connector[F, Kinesis] {

      type Raw = KinesisAsyncClientOp[F]
      override val underlying: KinesisAsyncClientOp[F] = kinesisAsyncClientOp

      override def consumeBatched[R >: Kinesis](source: Source[R]): Stream[F, List[CommittableMessage[F]]] =
        Stream.raiseError[F](new UnsupportedOperationException("Amazon Kinesis topic can't be consumed directly"))

      override def produce[R >: Kinesis](message: Message[R]): F[Unit] =
        message match {
          case Message(payload, dest @ KinesisDestination(name)) =>
            for {
              partitionKey <- KinesisAttributesProvider[F].getPartitionKey(payload, dest)
              _            <- kinesisAsyncClientOp
                                .putRecord(
                                  PutRecordRequest
                                    .builder()
                                    .streamName(name)
                                    .partitionKey(partitionKey)
                                    .data(SdkBytes.fromUtf8String(payload.text))
                                    .build()
                                )
                                .adaptError(KinesisClientException(s"Exception while sending a message [${message.payload}] on [$dest]", _))
            } yield ()
          case Message(_, unsupportedDestination)                =>
            ApplicativeThrow[F].raiseError(
              new UnsupportedOperationException(s"KinesisConnector does not support destination: $unsupportedDestination")
            )
        }

    }

}

final case class KinesisClientException(message: String, e: Throwable) extends Exception(message, e)
