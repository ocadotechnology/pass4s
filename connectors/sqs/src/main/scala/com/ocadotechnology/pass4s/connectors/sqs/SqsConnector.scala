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

package com.ocadotechnology.pass4s.connectors.sqs

import cats.ApplicativeThrow
import cats.Monad
import cats.MonadThrow
import cats.data.OptionT
import cats.effect.Async
import cats.effect.Resource
import cats.implicits.*
import com.ocadotechnology.pass4s.core.Message.Payload
import com.ocadotechnology.pass4s.core.*
import com.ocadotechnology.pass4s.core.groupId.GroupIdMeta
import fs2.Stream
import io.laserdisc.pure.sqs.tagless.SqsAsyncClientOp
import io.laserdisc.pure.sqs.tagless.Interpreter as SqsInterpreter
import org.typelevel.log4cats.Logger
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.sqs.SqsAsyncClient
import software.amazon.awssdk.services.sqs.SqsAsyncClientBuilder
import software.amazon.awssdk.services.sqs.model.DeleteMessageRequest
import software.amazon.awssdk.services.sqs.model.MessageAttributeValue
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest
import software.amazon.awssdk.services.sqs.model.SendMessageRequest

import java.net.URI
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*
import izumi.reflect.Tag
import izumi.reflect.macrortti.LightTypeTag

import scala.util.Try

private object util {

  def niceName(url: SqsUrl): String =
    Try(URI.create(url.value))
      .map(_.getPath)
      .map {
        case s"/$_/$queueName" => queueName
        case ""                => url.value
        case path              => path
      }
      .getOrElse(url.value)

}

trait Sqs

trait SqsFifo

object SqsFifo {
  val groupIdMetadata: String = "pass4s.sqs.groupId"
  val dedupIdMetadata: String = "pass4s.sqs.dedupId"

  implicit val groupIdMeta: GroupIdMeta[SqsFifo] = new GroupIdMeta[SqsFifo] {
    override def groupIdKey = groupIdMetadata
  }

}

final case class SqsUrl(value: String) extends AnyVal

sealed trait SqsSource[T >: Sqs & SqsFifo] extends Source[T] {
  def url: SqsUrl
  def settings: SqsSource.Settings

  override val name: String = util.niceName(url)
  override val messageProcessingTimeout: Option[FiniteDuration] = Some(settings.messageProcessingTimeout)
  override val cancelableMessageProcessing: Boolean = settings.cancelableMessageProcessing
  override val maxConcurrent: Int = settings.maxConcurrent
}

object SqsSource {
  def unapply(src: SqsSource[?]): Option[(SqsUrl, Settings)] = Some((src.url, src.settings))

  sealed trait Settings {
    def messageProcessingTimeout: FiniteDuration
    def cancelableMessageProcessing: Boolean
    def waitTimeSeconds: Int
    def maxNumberOfMessages: Int
    def maxConcurrent: Int
  }

  object Settings {

    def unapply(settings: Settings): Option[(FiniteDuration, Boolean, Int, Int, Int)] =
      Some(
        (
          settings.messageProcessingTimeout,
          settings.cancelableMessageProcessing,
          settings.waitTimeSeconds,
          settings.maxNumberOfMessages,
          settings.maxConcurrent
        )
      )

  }

}

final case class SqsEndpoint(url: SqsUrl, settings: SqsEndpoint.Settings = SqsEndpoint.Settings()) extends SqsSource[Sqs] {
  if (url.value.endsWith(".fifo")) throw new IllegalArgumentException("For fifo queues use SqsFifoEndpoint")
  override def capability: LightTypeTag = Tag[Sqs].tag
  def toDestination: SqsDestination = SqsDestination(url)
}

object SqsEndpoint {

  final case class Settings(
    // sets internal timeout on a message processing. SQS' visibilityTimeout will be (x + 1 second) * 1.2
    messageProcessingTimeout: FiniteDuration = 30.seconds,
    cancelableMessageProcessing: Boolean = true,
    waitTimeSeconds: Int = 3,
    maxNumberOfMessages: Int = 10,
    // for FIFO queues it is recommended to leave default value
    maxConcurrent: Int = 1
  ) extends SqsSource.Settings

}

final case class SqsFifoEndpoint(url: SqsUrl, settings: SqsFifoEndpoint.Settings = SqsFifoEndpoint.Settings()) extends SqsSource[SqsFifo] {
  if (!url.value.endsWith(".fifo")) throw new IllegalArgumentException("For non-fifo queues use SqsEndpoint")
  override def capability: LightTypeTag = Tag[SqsFifo].tag
  def toDestination: SqsFifoDestination = SqsFifoDestination(url)
}

object SqsFifoEndpoint {

  final case class Settings(
    // sets internal timeout on a message processing. SQS' visibilityTimeout will be (x + 1 second) * 1.2
    messageProcessingTimeout: FiniteDuration = 30.seconds,
    cancelableMessageProcessing: Boolean = true,
    waitTimeSeconds: Int = 3
  ) extends SqsSource.Settings {
    val maxNumberOfMessages = 1
    val maxConcurrent = 1
  }

}

final case class SqsDestination(url: SqsUrl) extends Destination[Sqs] {
  override val capability: LightTypeTag = Tag[Sqs].tag
  override val name: String = util.niceName(url)
  def toSource(settings: SqsEndpoint.Settings = SqsEndpoint.Settings()): SqsEndpoint = SqsEndpoint(url, settings)
}

final case class SqsFifoDestination(url: SqsUrl) extends Destination[SqsFifo] {
  override val capability: LightTypeTag = Tag[SqsFifo].tag
  override val name: String = util.niceName(url)
  def toSource(settings: SqsFifoEndpoint.Settings = SqsFifoEndpoint.Settings()): SqsFifoEndpoint = SqsFifoEndpoint(url, settings)
}

trait SqsAttributesProvider[F[_]] {
  def getGroupId(payload: Payload, destination: SqsFifoDestination): F[String]
  def getDedupId(payload: Payload, destination: SqsFifoDestination): F[Option[String]]
}

object SqsAttributesProvider {

  def apply[F[_]](
    implicit ev: SqsAttributesProvider[F]
  ): SqsAttributesProvider[F] = ev

  def default[F[_]: MonadThrow]: SqsAttributesProvider[F] =
    new SqsAttributesProvider[F] {

      override def getGroupId(payload: Payload, destination: SqsFifoDestination) =
        MonadThrow[F].catchNonFatal {
          Payload
            .getHeader(SqsFifo.groupIdMetadata)(payload)
            .getOrElse(throw new IllegalStateException("sqs fifo payloads must include group id metadata"))
        }

      override def getDedupId(payload: Payload, destination: SqsFifoDestination) =
        Monad[F].pure(Payload.getHeader(SqsFifo.dedupIdMetadata)(payload))
    }

}

object SqsConnector {
  type SqsConnector[F[_]] = Connector.Aux[F, Sqs & SqsFifo, SqsAsyncClientOp[F]]

  def usingLocalAws[F[_]: SqsAttributesProvider: Async: Logger](
    endpointOverride: URI,
    region: Region,
    credentialsProvider: AwsCredentialsProvider
  ): Resource[F, SqsConnector[F]] =
    usingBuilder(
      SqsAsyncClient.builder().endpointOverride(endpointOverride).region(region).credentialsProvider(credentialsProvider)
    )

  def usingLocalAwsWithDefaultAttributesProvider[F[_]: Async: Logger](
    endpointOverride: URI,
    region: Region,
    credentialsProvider: AwsCredentialsProvider
  ): Resource[F, SqsConnector[F]] = {
    implicit val sqsAttributesProvider: SqsAttributesProvider[F] = SqsAttributesProvider.default
    usingLocalAws(endpointOverride, region, credentialsProvider)
  }

  def usingRegion[F[_]: SqsAttributesProvider: Async: Logger](
    region: Region,
    endpointOverride: Option[URI] = None
  ): Resource[F, SqsConnector[F]] =
    usingBuilder {
      val builder = SqsAsyncClient.builder().region(region)
      endpointOverride.fold(builder)(builder.endpointOverride)
    }

  def usingRegionWithDefaultAttributesProvider[F[_]: Async: Logger](
    region: Region,
    endpointOverride: Option[URI] = None
  ): Resource[F, SqsConnector[F]] = {
    implicit val sqsAttributesProvider: SqsAttributesProvider[F] = SqsAttributesProvider.default
    usingRegion(region, endpointOverride)
  }

  def usingBuilder[F[_]: SqsAttributesProvider: Async: Logger](
    sqsBuilder: SqsAsyncClientBuilder
  ): Resource[F, SqsConnector[F]] =
    SqsInterpreter.apply.SqsAsyncClientOpResource(sqsBuilder).map(usingPureClient[F])

  def usingBuilderWithDefaultAttributesProvider[F[_]: Async: Logger](
    sqsBuilder: SqsAsyncClientBuilder
  ): Resource[F, SqsConnector[F]] = {
    implicit val sqsAttributesProvider: SqsAttributesProvider[F] = SqsAttributesProvider.default
    usingBuilder(sqsBuilder)
  }

  def usingPureClient[F[_]: SqsAttributesProvider: Async: Logger](sqsAsyncClientOp: SqsAsyncClientOp[F]): SqsConnector[F] =
    new Connector[F, Sqs & SqsFifo] {

      type Raw = SqsAsyncClientOp[F]
      val underlying: SqsAsyncClientOp[F] = sqsAsyncClientOp

      override def consumeBatched[R >: Sqs & SqsFifo](source: Source[R]): Stream[F, List[CommittableMessage[F]]] =
        source match {
          case sqsEndpoint: SqsSource[_] =>
            Stream
              .repeatEval(
                sqsAsyncClientOp
                  .receiveMessage(createReceiveMessageRequest(sqsEndpoint))
                  .adaptError(SqsClientException(s"Exception while receiving a message from [$sqsEndpoint]", _))
                  .attempt
              )
              .zipWithIndex
              .flatMap {
                case (Right(response), _)  => Stream.emit(response.messages().asScala.toList)
                case (Left(e), i) if i < 1 => Stream.raiseError[F](e)
                case (Left(e), _)          =>
                  Stream.exec(
                    Logger[F].warn(e)(
                      s"Failed to receive messages from SQS ($source). Since message consuming already stared error will be ignored. Caused by: $e"
                    )
                  ) ++ Stream.sleep_(sqsEndpoint.settings.waitTimeSeconds.seconds)
              }
              .map { messages =>
                messages.map { msg =>
                  val payload = Message.Payload(
                    msg.body(),
                    msg.messageAttributes().asScala.toMap.mapFilter(a => Option(a.stringValue())) // null check
                  )
                  val commit = sqsAsyncClientOp
                    .deleteMessage {
                      DeleteMessageRequest.builder().queueUrl(sqsEndpoint.url.value).receiptHandle(msg.receiptHandle()).build()
                    }
                    .adaptError(SqsClientException(s"Exception while deleting a message [$payload] on [$sqsEndpoint]", _))
                  CommittableMessage.instance(payload, commit.void, rollback = _ => ().pure[F])
                }
              }
          case unsupportedDestination    =>
            Stream.raiseError[F](new UnsupportedOperationException(s"SqsConnector does not support destination: $unsupportedDestination"))
        }

      @scala.annotation.nowarn // custom unapply breaks the exhaustiveness checking
      private val createReceiveMessageRequest: SqsSource[?] => ReceiveMessageRequest = {
        case SqsSource(sqsUrl, SqsSource.Settings(messageProcessingTimeout, _, waitTimeSeconds, maxNumberOfMessages, _)) =>
          ReceiveMessageRequest
            .builder()
            .waitTimeSeconds(waitTimeSeconds)
            .maxNumberOfMessages(maxNumberOfMessages)
            .visibilityTimeout(((messageProcessingTimeout + 1.second) * 1.2).toSeconds.toInt)
            .queueUrl(sqsUrl.value)
            .messageAttributeNames("All")
            .build()
      }

      private def makeRequest(destination: SqsDestination, payload: Payload) =
        SendMessageRequest
          .builder()
          .queueUrl(destination.url.value)
          .messageBody(payload.text)
          .messageAttributes(
            payload.metadata.fmap(MessageAttributeValue.builder().dataType("String").stringValue(_).build()).asJava
          )
          .build()

      private def makeFifoRequest(destination: SqsFifoDestination, payload: Payload) =
        for {
          initialRequest <- Monad[F].pure(
                              SendMessageRequest
                                .builder()
                                .queueUrl(destination.url.value)
                                .messageBody(payload.text)
                                .messageAttributes(
                                  payload.metadata.fmap(MessageAttributeValue.builder().dataType("String").stringValue(_).build()).asJava
                                )
                            )
          withGroupId    <- SqsAttributesProvider[F].getGroupId(payload, destination).map(initialRequest.messageGroupId)
          withDedupId    <- OptionT(SqsAttributesProvider[F].getDedupId(payload, destination))
                              .map(withGroupId.messageDeduplicationId)
                              .getOrElse(withGroupId)
        } yield withDedupId.build()

      override def produce[R >: Sqs & SqsFifo](message: Message[R]): F[Unit] =
        for {
          requestWithDestination <- message match {
                                      case Message(payload, d: SqsDestination)     =>
                                        (makeRequest(d, payload), d).pure[F]
                                      case Message(payload, d: SqsFifoDestination) =>
                                        makeFifoRequest(d, payload).tupleRight(d)
                                      case Message(_, unsupportedDestination)      =>
                                        ApplicativeThrow[F].raiseError(
                                          new UnsupportedOperationException(
                                            s"SqsConnector does not support destination: $unsupportedDestination"
                                          )
                                        )
                                    }
          (request, d) = requestWithDestination
          _                      <- sqsAsyncClientOp
                                      .sendMessage(request)
                                      .adaptError(SqsClientException(s"Exception while sending a message [${message.payload}] on [$d]", _))
        } yield ()

    }

}

final case class SqsClientException(message: String, e: Throwable) extends Exception(message, e)
