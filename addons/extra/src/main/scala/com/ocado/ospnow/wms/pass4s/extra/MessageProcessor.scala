package com.ocadotechnology.pass4s.extra

import cats.arrow.FunctionK
import cats.effect.Concurrent
import cats.effect.syntax.all._
import cats.syntax.all._
import cats.~>
import com.ocadotechnology.pass4s.core.Message
import com.ocadotechnology.pass4s.core.Source
import com.ocadotechnology.pass4s.high.Broker
import com.ocadotechnology.pass4s.kernel.Consumer

object MessageProcessor {

  def init[F[_]]: ConsumerModifier[F, Message.Payload, Message.Payload] = ConsumerModifier[F]()

  final class ConsumerModifier[F[_], A, B] private (f: Consumer[F, A] => Consumer[F, B]) {
    private[MessageProcessor] def execute(c: Consumer[F, A]): Consumer[F, B] = f(c)
    private[MessageProcessor] def map[C](g: Consumer[F, B] => Consumer[F, C]): ConsumerModifier[F, A, C] =
      new ConsumerModifier(f andThen g)
  }

  object ConsumerModifier {
    private def id[F[_], A]: ConsumerModifier[F, A, A] = new ConsumerModifier(identity)
    def apply[F[_]](): ConsumerModifier[F, Message.Payload, Message.Payload] = id[F, Message.Payload]

    implicit class ConsumerModifierSyntax[F[_], A, B](modifier: ConsumerModifier[F, A, B]) {
      def enrich[C](f: Consumer[F, B] => Consumer[F, C]): ConsumerModifier[F, A, C] =
        modifier.map(f)
    }

    implicit class BaseConsumerModifierSyntax[F[_], A](modifier: ConsumerModifier[F, Message.Payload, A]) {
      def transacted[T[_]](transact: T ~> F) = new ModifierWithTransaction(modifier, transact)
      def effectful = new ModifierWithTransaction(modifier, FunctionK.id[F])
    }
  }

  final class ModifierWithTransaction[F[_], T[_], A] private[MessageProcessor] (
    val modifier: ConsumerModifier[F, Message.Payload, A],
    val transact: T ~> F
  )

  object ModifierWithTransaction {
    implicit class ModifierWithTransactionSyntax[F[_]: Concurrent, T[_], A, P](modifierWithTransaction: ModifierWithTransaction[F, T, A]) {
      def bindBroker(broker: Broker[F, P]) = new MessageHandler(modifierWithTransaction.modifier, modifierWithTransaction.transact, broker)
    }

  }

  final class MessageHandler[F[_]: Concurrent, T[_], A, +P] private[MessageProcessor] (
    modifier: ConsumerModifier[F, Message.Payload, A],
    transact: T ~> F,
    broker: Broker[F, P]
  ) {

    def enrich[B](f: Consumer[F, A] => Consumer[F, B]): MessageHandler[F, T, B, P] =
      new MessageHandler(
        modifier.map(f),
        transact,
        broker
      )

    def handle[R >: P](
      source: Source[R]
    )(
      process: A => T[Unit]
    ) =
      modifier.execute(broker.consumer(source)).consumeCommitK(transact)(process).background.void

  }

}
