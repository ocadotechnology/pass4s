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

package com.ocadotechnology.pass4s.phobos

import cats.MonadThrow
import cats.syntax.all._
import com.ocadotechnology.pass4s.core.Destination
import com.ocadotechnology.pass4s.core.Message
import com.ocadotechnology.pass4s.core.groupId.GroupIdMeta
import com.ocadotechnology.pass4s.core.groupId.MessageGroup
import com.ocadotechnology.pass4s.kernel.Consumer
import com.ocadotechnology.pass4s.kernel.Sender
import ru.tinkoff.phobos.decoding.XmlDecoder
import ru.tinkoff.phobos.encoding.XmlEncoder

object syntax {

  final private[syntax] class AsXmlSenderPartiallyApplied[F[_], P, A](private val sender: Sender[F, Message[P]]) extends AnyVal {

    @scala.annotation.nowarn("cat=unused-params")
    def apply[R >: P](to: Destination[R])(implicit encoder: XmlEncoder[A], noGroupId: GroupIdMeta.Absent[R]): Sender[F, A] =
      sender.contramap(XmlMessage(_, to).widen)

  }

  final private[syntax] class AsXmlSenderWithCustomMetadataPartiallyApplied[F[_], P, A](private val sender: Sender[F, Message[P]])
    extends AnyVal {

    @scala.annotation.nowarn("cat=unused-params")
    def apply[R >: P](
      to: Destination[R],
      computeMetadata: A => Map[String, String]
    )(
      implicit encoder: XmlEncoder[A],
      noGroupId: GroupIdMeta.Absent[R]
    ): Sender[F, A] =
      sender.contramap(a => XmlMessage(a, to, computeMetadata(a)).widen)

  }

  final private[syntax] class AsXmlSenderWithMessageGroupPartiallyApplied[F[_], P, A](private val sender: Sender[F, Message[P]])
    extends AnyVal {

    def apply[R >: P](
      to: Destination[R],
      computeMetadata: A => Map[String, String] = _ => Map()
    )(
      implicit encoder: XmlEncoder[A],
      groupIdMeta: GroupIdMeta[R],
      messageGroup: MessageGroup[A]
    ): Sender[F, A] =
      sender.asXmlSenderWithCustomMetadata[A](to, a => Map(groupIdMeta.groupIdKey -> messageGroup.groupId(a)) ++ computeMetadata(a))(
        encoder,
        GroupIdMeta.Absent.iKnowWhatImDoing
      )

  }

  implicit final class SendXmlMessageSyntax[F[_], P](private val sender: Sender[F, Message[P]]) {

    /** ===params:===
      * {{{to: Destination[R >: P]}}}
      * ===implicit params:===
      * {{{encoder: XmlEncoder[A]}}} {{{noGroupId: GroupIdMeta.Absent[R >: P] // cannot be used for FIFO-aware destinations}}}
      */
    def asXmlSender[A] = new AsXmlSenderPartiallyApplied[F, P, A](sender)

    /** ===params:===
      * {{{to: Destination[R >: P]}}} {{{computeMetadata: A => Map[String, String]}}}
      * ===implicit params:===
      * {{{encoder: XmlEncoder[A]}}} {{{noGroupId: GroupIdMeta.Absent[R >: P] // cannot be used for FIFO-aware destinations}}}
      */
    def asXmlSenderWithCustomMetadata[A] = new AsXmlSenderWithCustomMetadataPartiallyApplied[F, P, A](sender)

    /** ===params:===
      * {{{to: Destination[R >: P]}}} {{{computeMetadata: A => Map[String, String] = _ => Map()}}}
      * ===implicit params:===
      * {{{encoder: XmlEncoder[A]}}} {{{messageGroup: MessageGroup[A]}}}
      * {{{groupIdMeta: GroupIdMeta[R >: P] // must be used with FIFO-aware destinations}}}
      */
    def asXmlSenderWithMessageGroup[A] = new AsXmlSenderWithMessageGroupPartiallyApplied[F, P, A](sender)
  }

  implicit final class ConsumeXmlMessageSyntax[F[_]](private val consumer: Consumer[F, String]) {
    def asXmlConsumer[A: XmlDecoder](implicit F: MonadThrow[F]): Consumer[F, A] =
      consumer.mapM(XmlDecoder[A].decode(_).liftTo[F])
  }

}
