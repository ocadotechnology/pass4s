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

package com.ocadotechnology.pass4s.circe

import cats.Defer
import cats.MonadError
import cats.implicits._
import com.ocadotechnology.pass4s.core.Destination
import com.ocadotechnology.pass4s.core.Message
import com.ocadotechnology.pass4s.core.Message.Payload
import com.ocadotechnology.pass4s.core.groupId.GroupIdMeta
import com.ocadotechnology.pass4s.core.groupId.MessageGroup
import com.ocadotechnology.pass4s.kernel.Consumer._
import com.ocadotechnology.pass4s.kernel._
import io.circe.Decoder
import io.circe.Encoder
import io.circe.parser.decode

// all of these would be available as extension methods for senders and consumers
object syntax {

  final private[syntax] class AsJsonSenderPartiallyApplied[F[_], P, A](private val sender: Sender[F, Message[P]]) extends AnyVal {

    def apply[R >: P](
      to: Destination[R]
    )(
      implicit encoder: Encoder[A],
      noGroupId: GroupIdMeta.Absent[R]
    ): Sender[F, A] =
      sender.contramap(JsonMessage(_, to).widen)

  }

  final private[syntax] class AsJsonSenderWithCustomMetadataPartiallyApplied[F[_], P, A](private val sender: Sender[F, Message[P]])
    extends AnyVal {

    def apply[R >: P](
      to: Destination[R],
      computeMetadata: A => Map[String, String]
    )(
      implicit encoder: Encoder[A],
      noGroupId: GroupIdMeta.Absent[R]
    ): Sender[F, A] =
      sender.contramap(a => JsonMessage(a, to, computeMetadata(a)).widen)

  }

  final private[syntax] class AsJsonSenderWithMessageGroupPartiallyApplied[F[_], P, A](private val sender: Sender[F, Message[P]])
    extends AnyVal {

    def apply[R >: P](
      to: Destination[R],
      computeMetadata: A => Map[String, String] = _ => Map()
    )(
      implicit encoder: Encoder[A],
      messageGroup: MessageGroup[A],
      groupIdMeta: GroupIdMeta[R]
    ): Sender[F, A] =
      sender.asJsonSenderWithCustomMetadata[A](to, a => Map(groupIdMeta.groupIdKey -> messageGroup.groupId(a)) ++ computeMetadata(a))(
        encoder,
        GroupIdMeta.Absent.iKnowWhatImDoing
      )

  }

  implicit final class SenderCirceExtensions[F[_], P](private val sender: Sender[F, Message[P]]) extends AnyVal {

    /** ===params:===
      * {{{to: Destination[R >: P]}}}
      * ===implicit params:===
      * {{{encoder: Encoder[A]}}} {{{noGroupId: GroupIdMeta.Absent[R >: P] // cannot be used for FIFO-aware destinations}}}
      */
    def asJsonSender[A] = new AsJsonSenderPartiallyApplied[F, P, A](sender)

    /** ===params:===
      * {{{to: Destination[R >: P]}}} {{{computeMetadata: A => Map[String, String]}}}
      * ===implicit params:===
      * {{{encoder: Encoder[A]}}} {{{noGroupId: GroupIdMeta.Absent[R >: P] // cannot be used for FIFO-aware destinations}}}
      */
    def asJsonSenderWithCustomMetadata[A] = new AsJsonSenderWithCustomMetadataPartiallyApplied[F, P, A](sender)

    /** ===params:===
      * {{{to: Destination[R >: P]}}} {{{computeMetadata: A => Map[String, String] = _ => Map()}}}
      * ===implicit params:===
      * {{{encoder: Encoder[A]}}} {{{messageGroup: MessageGroup[A]}}}
      * {{{groupIdMeta: GroupIdMeta[R >: P] // must be used with FIFO-aware destinations}}}
      */
    def asJsonSenderWithMessageGroup[A] = new AsJsonSenderWithMessageGroupPartiallyApplied[F, P, A](sender)
  }

  implicit final class ConsumerCirceExtensions[F[_], A](private val consumer: Consumer[F, A]) extends AnyVal {

    def asJsonConsumer[B: Decoder](
      implicit M: MonadError[F, _ >: io.circe.Error],
      ev: A <:< Payload
    ): Consumer[F, B] =
      consumer.mapM(msg => decode[B](msg.text).liftTo[F])

    def asJsonConsumerWithMessage[B: Decoder](
      implicit M: MonadError[F, _ >: io.circe.Error],
      D: Defer[F],
      ev: A <:< Payload
    ): Consumer[F, (A, B)] =
      consumer.selfProduct(_.asJsonConsumer)

  }

}
