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

package com.ocadotechnology.pass4s.s3proxy

import cats.Monad
import cats.MonadThrow
import cats.effect.std.UUIDGen
import cats.syntax.all._
import com.ocadotechnology.pass4s.core.Message
import com.ocadotechnology.pass4s.core.Message.Payload
import com.ocadotechnology.pass4s.kernel.Consumer
import com.ocadotechnology.pass4s.kernel.Sender
import io.circe.parser.decode
import io.circe.syntax._

object syntax {

  implicit final class ProxiedSenderSyntax[F[_], P](private val underlying: Sender[F, Message[P]]) extends AnyVal {

    def usingS3Proxy(
      config: S3ProxyConfig.Sender
    )(
      implicit s3Client: S3Client[F],
      uuid: UUIDGen[F],
      F: Monad[F]
    ): Sender[F, Message[P]] =
      underlying.contramapM { msg =>
        if (s3.shouldSendToS3(config.minPayloadSize, msg.payload))
          for {
            s3Key <- uuid.randomUUID.map(_.toString())
            _     <- s3Client.putObject(config.bucket, s3Key)(msg.payload.text)
            pointer = PayloadS3Pointer(config.bucket, s3Key)
            updatedMsg = s3.replacePayloadWithPointer(config, msg, pointer)
          } yield updatedMsg
        else msg.pure[F]
      }

  }

  implicit final class ProxiedConsumerSyntax[F[_], A](private val underlying: Consumer[F, Payload]) extends AnyVal {

    def usingS3Proxy[P](
      config: S3ProxyConfig.Consumer
    )(
      implicit s3Client: S3Client[F],
      F: MonadThrow[F]
    ): Consumer[F, Payload] =
      underlying
        .map(msg => (msg, decode[PayloadS3Pointer](msg.text).toOption))
        .afterEach { case (_, maybePointer) =>
          maybePointer.traverse(removeDataFromS3).void.whenA(config.shouldDeleteAfterProcessing)
        }
        .mapM {
          case (msg, Some(pointer)) => retrieveMsgFromS3(config)(msg, pointer)
          case (msg, None)          => msg.pure[F]
        }

    private def retrieveMsgFromS3(
      config: S3ProxyConfig.Consumer
    )(
      msg: Message.Payload,
      pointer: PayloadS3Pointer
    )(
      implicit s3Client: S3Client[F],
      F: MonadThrow[F]
    ): F[Message.Payload] =
      for {
        maybePayload <- s3Client.getObject(pointer.s3BucketName, pointer.s3Key)
        payload      <- F.fromOption(
                          maybePayload,
                          new NoSuchElementException(s"Key ${pointer.s3Key} in bucket ${pointer.s3BucketName} not found")
                        )
        message = s3.replacePointerWithPayload(config, msg, payload)
      } yield message

    private def removeDataFromS3(
      pointer: PayloadS3Pointer
    )(
      implicit s3Client: S3Client[F]
    ): F[Unit] =
      s3Client.deleteObject(pointer.s3BucketName, pointer.s3Key)

  }

  private object s3 {

    def replacePointerWithPayload(config: S3ProxyConfig.Consumer, payload: Message.Payload, payloadText: String): Message.Payload = {
      val updatedMetadata = config.payloadSizeAttributeName.fold(payload.metadata) { key =>
        payload.metadata - key
      }
      payload
        .copy(
          text = payloadText,
          metadata = updatedMetadata
        )
    }

    def replacePayloadWithPointer[P](config: S3ProxyConfig.Sender, msg: Message[P], pointer: PayloadS3Pointer): Message[P] = {
      val originalPayloadSize = calculatePayloadBytesSize(msg.payload)
      val updatedMetadata = config.payloadSizeAttributeName.fold(msg.payload.metadata) { key =>
        msg.payload.metadata + (key -> originalPayloadSize.toString())
      }
      val updatedPayload = msg
        .payload
        .copy(
          text = pointer.asJson.toString(),
          metadata = updatedMetadata
        )
      msg.copy(payload = updatedPayload)
    }

    def shouldSendToS3(minPayloadSize: Option[Long], payload: Message.Payload): Boolean =
      calculatePayloadBytesSize(payload) >= minPayloadSize.getOrElse(0L)

    def calculatePayloadBytesSize(payload: Message.Payload): Long =
      payload
        .metadata
        .foldLeft(sizeInBytes(payload.text)) { case (acc, (k, v)) =>
          acc + sizeInBytes(k) + sizeInBytes(v)
        }

    // TODO consider rewriting to something relying on CountingOutputStream https://github.com/awslabs/payload-offloading-java-common-lib-for-aws/blob/7c826fccce39c589d06abffa0c7f912115212dba/src/main/java/software/amazon/payloadoffloading/CountingOutputStream.java
    private def sizeInBytes(str: String): Long = str.getBytes().length.toLong
  }

}
