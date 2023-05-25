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

package com.ocadotechnology.pass4s.connectors.sns

import cats.ApplicativeThrow
import cats.Monad
import cats.MonadThrow
import cats.data.OptionT
import cats.effect.Async
import cats.effect.Resource
import cats.implicits._
import com.ocadotechnology.pass4s.core.Message.Payload
import com.ocadotechnology.pass4s.core._
import com.ocadotechnology.pass4s.core.groupId.GroupIdMeta
import fs2.Stream
import io.laserdisc.pure.sns.tagless.SnsAsyncClientOp
import io.laserdisc.pure.sns.tagless.{Interpreter => SnsInterpreter}
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.sns.SnsAsyncClient
import software.amazon.awssdk.services.sns.SnsAsyncClientBuilder
import software.amazon.awssdk.services.sns.model.MessageAttributeValue
import software.amazon.awssdk.services.sns.model.PublishRequest

import java.net.URI
import scala.jdk.CollectionConverters._
import scala.reflect.runtime.universe._

trait Sns

final case class SnsArn(value: String) extends AnyVal

final case class SnsDestination(arn: SnsArn) extends Destination[Sns] {
  if (arn.value.endsWith(".fifo")) throw new IllegalArgumentException("For FIFO topics use SnsFifoDestination")

  override val capability: Type = typeOf[Sns]

  override val name: String = arn.value match {
    case s"arn:aws:sns:$_:$_:$topicName" => topicName
    case other                           => other
  }

}

trait SnsFifo

object SnsFifo {
  val groupIdMetadata: String = "pass4s.sns.groupId"
  val dedupIdMetadata: String = "pass4s.sns.dedupId"

  implicit val groupIdMeta: GroupIdMeta[SnsFifo] = new GroupIdMeta[SnsFifo] {
    override def groupIdKey = groupIdMetadata
  }

}

final case class SnsFifoDestination(arn: SnsArn) extends Destination[SnsFifo] {
  if (!arn.value.endsWith(".fifo")) throw new IllegalArgumentException("For non-FIFO topics use SnsDestination")

  override val capability: Type = typeOf[Sns]

  override val name: String = arn.value match {
    case s"arn:aws:sns:$_:$_:$topicName" => topicName
    case other                           => other
  }

}

trait SnsAttributesProvider[F[_]] {
  def getGroupId(payload: Payload, destination: SnsFifoDestination): F[String]
  def getDedupId(payload: Payload, destination: SnsFifoDestination): F[Option[String]]
}

object SnsAttributesProvider {
  def apply[F[_]](implicit ev: SnsAttributesProvider[F]): SnsAttributesProvider[F] = ev

  def default[F[_]: MonadThrow]: SnsAttributesProvider[F] =
    new SnsAttributesProvider[F] {

      override def getGroupId(payload: Payload, destination: SnsFifoDestination) =
        MonadThrow[F].catchNonFatal {
          Payload
            .getHeader(SnsFifo.groupIdMetadata)(payload)
            .getOrElse(throw new IllegalStateException("sns fifo payloads must include group id metadata"))
        }

      override def getDedupId(payload: Payload, destination: SnsFifoDestination) =
        Monad[F].pure(Payload.getHeader(SnsFifo.dedupIdMetadata)(payload))
    }

}

object SnsConnector {
  type SnsConnector[F[_]] = Connector.Aux[F, Sns with SnsFifo, SnsAsyncClientOp[F]]
  type AllSns = Sns with SnsFifo

  def usingLocalAws[F[_]: SnsAttributesProvider: Async](
    endpointOverride: URI,
    region: Region,
    credentialsProvider: AwsCredentialsProvider
  ): Resource[F, SnsConnector[F]] =
    usingBuilder(
      SnsAsyncClient.builder().endpointOverride(endpointOverride).region(region).credentialsProvider(credentialsProvider)
    )

  def usingLocalAwsWithDefaultAttributesProvider[F[_]: Async](
    endpointOverride: URI,
    region: Region,
    credentialsProvider: AwsCredentialsProvider
  ): Resource[F, SnsConnector[F]] = {
    implicit val snsAttributesProvider: SnsAttributesProvider[F] = SnsAttributesProvider.default
    usingLocalAws(endpointOverride, region, credentialsProvider)
  }

  def usingRegion[F[_]: SnsAttributesProvider: Async](
    region: Region,
    endpointOverride: Option[URI] = None
  ): Resource[F, SnsConnector[F]] =
    usingBuilder {
      val builder = SnsAsyncClient.builder().region(region)
      endpointOverride.fold(builder)(builder.endpointOverride)
    }

  def usingRegionWithDefaultAttributesProvider[F[_]: Async](
    region: Region,
    endpointOverride: Option[URI] = None
  ): Resource[F, SnsConnector[F]] = {
    implicit val snsAttributesProvider: SnsAttributesProvider[F] = SnsAttributesProvider.default
    usingRegion(region, endpointOverride)
  }

  def usingBuilder[F[_]: SnsAttributesProvider: Async](
    snsBuilder: SnsAsyncClientBuilder
  ): Resource[F, SnsConnector[F]] =
    SnsInterpreter.apply.SnsAsyncClientOpResource(snsBuilder).map(usingPureClient[F])

  def usingBuilderWithDefaultAttributesProvider[F[_]: Async](
    snsBuilder: SnsAsyncClientBuilder
  ): Resource[F, SnsConnector[F]] = {
    implicit val snsAttributesProvider: SnsAttributesProvider[F] = SnsAttributesProvider.default
    usingBuilder(snsBuilder)
  }

  def usingPureClient[F[_]: SnsAttributesProvider: MonadThrow](snsAsyncClientOp: SnsAsyncClientOp[F]): SnsConnector[F] =
    new Connector[F, AllSns] {

      type Raw = SnsAsyncClientOp[F]
      override val underlying: SnsAsyncClientOp[F] = snsAsyncClientOp

      override def consumeBatched[R >: AllSns](source: Source[R]): Stream[F, List[CommittableMessage[F]]] =
        Stream.raiseError[F](new UnsupportedOperationException("Amazon SNS topic can't be consumed directly"))

      private def makeRequest(destination: SnsDestination, payload: Payload) =
        PublishRequest
          .builder()
          .topicArn(destination.arn.value)
          .message(payload.text)
          .messageAttributes(
            payload.metadata.fmap(MessageAttributeValue.builder().dataType("String").stringValue(_).build()).asJava
          )
          .build()

      private def makeFifoRequest(destination: SnsFifoDestination, payload: Payload) =
        for {
          initialRequest <- Monad[F].pure(
                              PublishRequest
                                .builder()
                                .topicArn(destination.arn.value)
                                .message(payload.text)
                                .messageAttributes(
                                  payload.metadata.fmap(MessageAttributeValue.builder().dataType("String").stringValue(_).build()).asJava
                                )
                            )

          withGroupId <- SnsAttributesProvider[F].getGroupId(payload, destination).map(initialRequest.messageGroupId)
          withDedupId <- OptionT(SnsAttributesProvider[F].getDedupId(payload, destination))
                           .map(withGroupId.messageDeduplicationId)
                           .getOrElse(withGroupId)
        } yield withDedupId.build()

      override def produce[R >: AllSns](message: Message[R]): F[Unit] =
        for {
          (request, d) <- message match {
                            case Message(payload, d: SnsDestination)     =>
                              (makeRequest(d, payload), d).pure[F]
                            case Message(payload, d: SnsFifoDestination) =>
                              makeFifoRequest(d, payload).tupleRight(d)
                            case Message(_, unsupportedDestination)      =>
                              ApplicativeThrow[F].raiseError(
                                new UnsupportedOperationException(s"SnsConnector does not support destination: $unsupportedDestination")
                              )
                          }
          _            <- snsAsyncClientOp
                            .publish(request)
                            .adaptError(SnsClientException(s"Exception while sending a message [${message.payload}] on [$d]", _))
        } yield ()

    }

}

final case class SnsClientException(message: String, e: Throwable) extends Exception(message, e)
