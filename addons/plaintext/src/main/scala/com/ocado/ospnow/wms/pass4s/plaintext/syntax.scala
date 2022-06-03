package com.ocadotechnology.pass4s.plaintext

import com.ocadotechnology.pass4s.core.Destination
import com.ocadotechnology.pass4s.core.Message
import com.ocadotechnology.pass4s.core.groupId.GroupIdMeta
import com.ocadotechnology.pass4s.core.groupId.MessageGroup
import com.ocadotechnology.pass4s.kernel.Sender

object syntax {

  final private[syntax] class AsPlaintextSenderPartiallyApplied[F[_], P](private val sender: Sender[F, Message[P]]) extends AnyVal {

    @scala.annotation.nowarn("cat=unused-params")
    def apply[R >: P](
      to: Destination[R],
      computeMetadata: String => Map[String, String] = _ => Map()
    )(
      implicit noGroupId: GroupIdMeta.Absent[R]
    ): Sender[F, String] =
      sender.contramap(a => Message(Message.Payload(a, computeMetadata(a)), to).widen)

  }

  final private[syntax] class AsPlaintextSenderWithMessageGroupPartiallyApplied[F[_], P](private val sender: Sender[F, Message[P]])
    extends AnyVal {

    def apply[R >: P](
      to: Destination[R],
      messageGroup: MessageGroup[String], // explicitly because one message group for strings doesn't make sense
      computeMetadata: String => Map[String, String] = _ => Map()
    )(
      implicit groupIdMeta: GroupIdMeta[R]
    ): Sender[F, String] =
      sender.asPlaintextSender(to, a => Map(groupIdMeta.groupIdKey -> messageGroup.groupId(a)) ++ computeMetadata(a))(
        GroupIdMeta.Absent.iKnowWhatImDoing
      )

  }

  implicit final class SendPlaintextMessageSyntax[F[_], P](private val sender: Sender[F, Message[P]]) {

    /** === params: ===
      * {{{to: Destination[R >: P]}}}
      * {{{computeMetadata: String => Map[String, String] = _ => Map()}}}
      * === implicit params: ===
      * {{{noGroupId: GroupIdMeta.Absent[R >: P] // cannot be used for FIFO-aware destinations}}}
      */
    def asPlaintextSender = new AsPlaintextSenderPartiallyApplied[F, P](sender)

    /** === params: ===
      * {{{to: Destination[R >: P]}}}
      * {{{messageGroup: MessageGroup[String] // explicit! because it doesn't make sense to use one implicit for all Strings}}}
      * {{{computeMetadata: String => Map[String, String] = _ => Map()}}}
      * === implicit params: ===
      * {{{groupIdMeta: GroupIdMeta[R >: P] // must be used with FIFO-aware destinations}}}
      */
    def asPlaintextSenderWithMessageGroup = new AsPlaintextSenderWithMessageGroupPartiallyApplied[F, P](sender)
  }

}
