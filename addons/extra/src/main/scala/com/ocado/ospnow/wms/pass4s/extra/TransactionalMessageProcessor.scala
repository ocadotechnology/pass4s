package com.ocado.ospnow.wms.pass4s.extra

import cats.Defer
import cats.effect.Concurrent
import cats.effect.Resource
import cats.effect.syntax.all._
import cats.syntax.all._
import cats.~>
import com.ocado.ospnow.wms.pass4s.circe.syntax._
import com.ocado.ospnow.wms.pass4s.core.Message
import com.ocado.ospnow.wms.pass4s.core.Source
import com.ocado.ospnow.wms.pass4s.high.Broker
import com.ocado.ospnow.wms.pass4s.kernel.Consumer
import io.circe.Decoder

/**
  * A high-level interface for quickly creating consumers that fit the major usecase: transactional processing.
  * Methods in a TransactionalMessageProcessor[T, F] allow processing a message in the effect T, while using a broker of type F.
  * For example, T may be ConnectionIO or a zio.TaskR[Transaction, *], and F would be IO or zio.Task.
  *
  * Additionally, the methods here return resources which start a consumer during allocation and stop it during shutdown, to ease composition.
  * These resources can be composed sequentially (consumer1 *> consumer2), as each of them starts a background process.
  *
  * @tparam T the effect of processing a single message, can be transactional
  * @tparam F the effect of running a consumer, should usually be equivalent to IO or Task
  */
@deprecated("This interface will be dropped in the next release. Use MessageProcessor instead.", since="11.0.0")
trait TransactionalMessageProcessor[T[_], F[_], +P] {

  def jsonConsumerWithMessage[A: Decoder, R >: P](source: Source[R])(process: (String, A) => T[Unit]): Resource[F, Unit]

  def jsonConsumer[A: Decoder, R >: P](source: Source[R])(process: A => T[Unit]): Resource[F, Unit] =
    jsonConsumerWithMessage[A, R](source)((_, a) => process(a))

  def textConsumer[R >: P](source: Source[R])(process: String => T[Unit]): Resource[F, Unit]

  /** useful if you want to add tracing or logging to the underlying consumers (via pass4s-tracing's TracingSyntax) */
  def modify(f: Consumer[F, Message.Payload] => Consumer[F, Message.Payload]): TransactionalMessageProcessor[T, F, P]
}

object TransactionalMessageProcessor {
  def apply[DB[_], F[_], P](implicit ev: TransactionalMessageProcessor[DB, F, P]): ev.type = ev

  /**
    * Returns a message processor capable of starting consumers in F (using the given broker),
    * but allowing the processing function to be of a different effect, as long as it can be converted to F.
    */
  def instance[T[_], F[_]: Concurrent: Defer, P](broker: Broker[F, P])(transact: T ~> F): TransactionalMessageProcessor[T, F, P] =
    new DefaultTransactionalMessageProcessor[T, F, P](broker, transact, List.empty)

  class DefaultTransactionalMessageProcessor[T[_], F[_]: Concurrent: Defer, +P](
    broker: Broker[F, P],
    transact: T ~> F,
    consumerModifiers: List[Consumer[F, Message.Payload] => Consumer[F, Message.Payload]]
  ) extends TransactionalMessageProcessor[T, F, P] {

    override def jsonConsumerWithMessage[A: Decoder, R >: P](
      source: Source[R]
    )(
      process: (String, A) => T[Unit]
    ): Resource[F, Unit] =
      consumerModifiers
        .foldLeft(broker.consumer(source))((consumer, f) => f(consumer))
        .asJsonConsumerWithMessage[A]
        .map(_.leftMap(_.text))
        .consumeCommitK(transact)(process.tupled)
        .background
        .void

    override def textConsumer[R >: P](source: Source[R])(process: String => T[Unit]): Resource[F, Unit] =
      consumerModifiers
        .foldLeft(broker.consumer(source))((consumer, f) => f(consumer))
        .map(_.text)
        .consumeCommitK(transact)(process)
        .background
        .void

    override def modify(f: Consumer[F, Message.Payload] => Consumer[F, Message.Payload]) =
      new DefaultTransactionalMessageProcessor(broker, transact, consumerModifiers :+ f)
  }

}
