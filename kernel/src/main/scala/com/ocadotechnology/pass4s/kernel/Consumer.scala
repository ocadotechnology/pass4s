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

package com.ocadotechnology.pass4s.kernel

import cats.Applicative
import cats.Apply
import cats.Defer
import cats.FlatMap
import cats.Foldable
import cats.Functor
import cats.FunctorFilter
import cats.InvariantMonoidal
import cats.Monad
import cats.NonEmptyParallel
import cats.Parallel
import cats.StackSafeMonad
import cats.effect.Async
import cats.effect.Concurrent
import cats.effect.Resource
import cats.effect.implicits.*
import cats.effect.kernel.Sync
import cats.effect.std.Queue
import cats.implicits.*
import cats.kernel.Eq
import cats.kernel.Monoid
import cats.kernel.Semigroup
import cats.tagless.InvariantK
import cats.~>
import fs2.Stream

/** A runnable consumer of A. To start one, you need a function that will handle messages of type A and return effects in F.
  *
  * For transactional processing, see [[Consumer#consumeCommit]] or high-level utilities like MessageProcessor in the `extra` module.
  */
trait Consumer[F[_], +A] extends ((A => F[Unit]) => F[Unit]) with Serializable { self =>

  //
  //
  // Eliminators - these methods are allowed to return effects/resources etc.
  // See http://marcosh.github.io/post/2020/01/16/converting-functional-data-structures-into-objects.html for definitions
  //
  //

  /** Starts the consumer, passing every message through the processing function `f`. Think of it like of an `evalMap` on [[fs2.Stream]] or
    * `use` on [[cats.effect.Resource]].
    *
    * For consumers built from an infinite-stream Connector (which every connector you ever use is going to be), this never terminates. For
    * consumers like Consumer.one or Consumer.finite, this will succeed when all the messages have completed.
    *
    * There doesn't need to be any error handling in consumers in general, but Consumer.sequential (used in any consumer built from a
    * Connector) handles errors and uses them to rollback messages.
    */
  def consume(f: A => F[Unit]): F[Unit]

  /** Starts the consumer, but allows the processing function `f` to be in a different effect than that of the consumer's. A `commit`
    * function also needs to be passed - it will be used after every message.
    */
  def consumeCommit[T[_]](commit: T[Unit] => F[Unit])(f: A => T[Unit]): F[Unit] = self.consume(f andThen commit)

  /** Like consumeCommit, but takes a FunctionK.
    */
  def consumeCommitK[T[_]](commitK: T ~> F)(f: A => T[Unit]): F[Unit] = consumeCommit(commitK.apply)(f)

  /** Alias for [[consume]] - thanks to this, you can pass a consumer where a function type is expected.
    */
  def apply(f: A => F[Unit]): F[Unit] = consume(f)

  /** Uses the given sender as the processing function. Could be useful for creating simple message forwarders between queues, brokers or
    * even distinct platforms.
    *
    * Both [[Consumer]] and [[Sender]] are functions though, so you could just call `apply(sender)` or `consume(sender)` directly.
    */
  def forward(sender: Sender[F, A]): F[Unit] = consume(sender)

  //
  //
  // Combinators - all these methods should return other Consumers.
  // More methods are available if you import the Cats syntax for functors/monads - see companion for available instances.
  // Some methods are also available via implicit extensions, as they have extra constraints.
  //
  //

  /** Passes every message through the given function before it lands in the processing function passed to the new consumer.
    */
  def map[B](f: A => B): Consumer[F, B] =
    handleB => consume(handleB.compose(f))

  // Wraps each message's processing effect in `f`.
  def surroundEach(f: F[Unit] => F[Unit]): Consumer[F, A] = surroundEachWith(_ => f)
  def surroundEachK(fk: F ~> F): Consumer[F, A] = surroundEach(fk(_))

  // Like `surroundEach`, but also provides the message that caused the effect.
  def surroundEachWith(f: A => F[Unit] => F[Unit]): Consumer[F, A] = use => self.consume(msg => f(msg)(use(msg)))
  def surroundEachWithK(fk: A => F ~> F): Consumer[F, A] = surroundEachWith(fk(_).apply)

  // Wraps the entire consumer's effect in `f` - so `f` will run exactly once per consumer, not once per message.
  def surroundAll(f: F[Unit] => F[Unit]): Consumer[F, A] = use => f(self.consume(use))
  def surroundAllK(f: F ~> F): Consumer[F, A] = surroundAll(f.apply)

  def imapK[G[_]](fk: F ~> G)(gk: G ~> F): Consumer[G, A] = f => fk(consume(a => gk(f(a))))
}

object Consumer extends ConsumerInstances {

  def apply[F[_], A](
    implicit C: Consumer[F, A]
  ): Consumer[F, A] = C

  /** A consumer that waits for processing of one message (and finalization of its resources), then takes another message from the stream of
    * resources. Failures that happen in the message handler are caught and used to rollback the message being processed.
    *
    * Restarts of the stream aren't handled here - this should be taken care of by the Connector implementation.
    */
  def sequential[F[_]: Sync, A](
    cancelableMessageProcessing: Boolean = true
  )(
    messages: Stream[F, Resource[F, A]]
  ): Consumer[F, A] = {
    /*
     * .attempt here ensures a rolled back message doesn't fail the entire stream.
     * It's the only place where we can do it really:
     * If we add .attempt in a combinator on Consumer, the failure will never reach the finalizer of the resource.
     * Thus, it needs to be done *after* the resource has been used.
     */
    val wrapMessageHandling: F[Unit] => F[Unit] = if (cancelableMessageProcessing) identity else _.uncancelable
    handler => messages.evalMap(message => wrapMessageHandling(message.use(handler)).attempt).compile.drain
  }

  /** A consumer that can handle more than 1 message at once. Failures that happen in the message handler are caught and used to rollback
    * the message being processed.
    *
    * Restarts of the stream aren't handled here - this should be taken care of by the Connector implementation.
    *
    * Keep in mind that processing of message may end up faster that the processing of the previous message. This strategy is not
    * recommended when using FIFO queues.
    */
  def paralleled[F[_]: Concurrent, A](
    maxConcurrent: Int,
    cancelableMessageProcessing: Boolean = true
  )(
    messages: Stream[F, Resource[F, A]]
  ): Consumer[F, A] = {
    val wrapMessageHandling: F[Unit] => F[Unit] = if (cancelableMessageProcessing) identity else _.uncancelable
    handler => messages.parEvalMapUnordered(maxConcurrent)(message => wrapMessageHandling(message.use(handler)).attempt).compile.drain
  }

  /** TODO: this is actually unused for now until we figure out how to properly handle failure and retries in SQS A consumer that processes
    * batches of messages in parallel. Messages within a batch are processed sequentially. Failures that happen in the message handler are
    * caught and used to rollback the message being processed.
    *
    * Restarts of the stream aren't handled here - this should be taken care of by the Connector implementation.
    *
    * This strategy should be safe for FIFO messages iff the broker ensures that messages in the batch are in FIFO order and that messages
    * with the same group id won't be seen in other batches before all the messages in the batch are committed. SQS is one example of such
    * message broker.
    */
  def parallelBatched[F[_]: Concurrent, A](
    maxConcurrent: Int,
    cancelableMessageProcessing: Boolean = true
  )(
    messages: Stream[F, List[Resource[F, A]]]
  ): Consumer[F, A] = {
    val wrapMessageHandling: F[Unit] => F[Unit] = if (cancelableMessageProcessing) identity else _.uncancelable
    handler =>
      messages
        .parEvalMapUnordered(maxConcurrent)(batch => batch.traverse_(message => wrapMessageHandling(message.use(handler)).attempt))
        .compile
        .drain
  }

  // Consumes the message provided here, then completes.
  def one[F[_], A](message: A): Consumer[F, A] = _.apply(message)

  // Alias for `one`.
  def pure[F[_], A](message: A): Consumer[F, A] = one(message)

  // Consumes a finite sequence of messages with the handler given to it, then completes.
  def finite[F[_]: Applicative, G[_]: Foldable, A](messages: G[A]): Consumer[F, A] = messages.traverse_(_)

  // Like [[finite]], but takes a varargs sequence.
  def many[F[_]: Applicative, A](messages: A*): Consumer[F, A] = finite(messages.toList)

  // A consumer that immediately completes handling when used, ignoring all messages.
  def done[F[_]: InvariantMonoidal]: Consumer[F, Nothing] = _ => InvariantMonoidal[F].unit

  // A consumer that handles forever, never actually doing anything.
  def eternal[F[_]: Async]: Consumer[F, Nothing] = _ => Async[F].never

  // Builds a consumer from a function that can be used to start it.
  def fromFunction[F[_], A](f: ((A => F[Unit]) => F[Unit])): Consumer[F, A] = f(_)

  /** TODO: Rewrite using the new Scala 3 type lambda syntax when the codebase moves to support Scala 3 source-specific directories:
    * {{{
    * given invariantK[A]: InvariantK[[F[_]] =>> Consumer[F, A]] with {
    *   def imapK[F[_], G[_]](af: Consumer[F, A])(fk: F ~> G)(gk: G ~> F): Consumer[G, A] = af.imapK(fk)(gk)
    * }
    * }}}
    *
    * See related discussion: https://github.com/ocadotechnology/pass4s/pull/542#discussion_r2053522966
    */
  /** [[cats.tagless.InvariantK]] instance for Consumer. The effect appears in both covariant and contravariant positions, so we can't get
    * anything stronger like [[cats.tagless.FunctorK]].
    */
  implicit def invariantK[A]: InvariantK[({ type C[F[_]] = Consumer[F, A] })#C] =
    new InvariantK[({ type C[F[_]] = Consumer[F, A] })#C] {
      def imapK[F[_], G[_]](af: Consumer[F, A])(fk: F ~> G)(gk: G ~> F): Consumer[G, A] = af.imapK(fk)(gk)
    }

  /** FunctorFilter instance for Consumer - allows ignoring certain messages, so they never reach the processing function passed afterwards.
    */
  implicit def functorFilter[F[_]: InvariantMonoidal]: FunctorFilter[Consumer[F, *]] =
    new FunctorFilter[Consumer[F, *]] {
      val functor: Functor[Consumer[F, *]] = Consumer.functor[F]

      def mapFilter[A, B](fa: Consumer[F, A])(f: A => Option[B]): Consumer[F, B] =
        fromFunction { handle =>
          fa.consume {
            f(_).fold(InvariantMonoidal[F].unit)(handle)
          }
        }

    }

  def toStreamSynchronous[F[_]: Concurrent, A](consumer: Consumer[F, A]): Stream[F, A] =
    Stream.eval(Queue.synchronous[F, A]).flatMap(toUncancelableStreamUsingQueue(_, consumer))

  def toStreamBounded[F[_]: Concurrent, A](maxSize: Int)(consumer: Consumer[F, A]): Stream[F, A] =
    Stream.eval(Queue.bounded[F, A](maxSize)).flatMap(toStreamUsingQueue(_, consumer))

  def toStreamUnbounded[F[_]: Concurrent, A](consumer: Consumer[F, A]): Stream[F, A] =
    Stream.eval(Queue.unbounded[F, A]).flatMap(toStreamUsingQueue(_, consumer))

  private def toStreamUsingQueue[F[_]: Concurrent, A](queue: Queue[F, A], consumer: Consumer[F, A]): Stream[F, A] =
    for {
      _ <- Stream.resource(consumer.consume(queue.offer).attempt.background)
      a <- Stream.fromQueueUnterminated(queue)
    } yield a

  private def toUncancelableStreamUsingQueue[F[_]: Concurrent, A](queue: Queue[F, A], consumer: Consumer[F, A]): Stream[F, A] = {
    val wrappedConsume = consumer.afterEach(queue.offer(_).uncancelable).consume(_ => ().pure[F])
    for {
      _ <- Stream.resource(wrappedConsume.background)
      a <- Stream.fromQueueUnterminated(queue)
    } yield a
  }

  /** Syntax enrichments for Consumer that can't be implemented directly in the trait due to additional constraints.
    */
  implicit final class ConsumerOps[F[_], A](private val self: Consumer[F, A]) extends AnyVal {

    /** Adds an additional layer of processing before the message is sent to the handler (Kleisli composition before the handler). Errors
      * are reported normally and have the same impact on the consumer's process as usual processing errors would, meaning they also usually
      * trigger a rollback.
      */
    def mapM[B](
      f: A => F[B]
    )(
      implicit F: FlatMap[F]
    ): Consumer[F, B] =
      handler => self.consume(f >=> handler)

    /** Allows to filter certain messages and execute an effect while doing it.
      *
      * For filtering without an effect use [[functorFilter]] instance.
      */
    def evalMapFilter[B](
      f: A => F[Option[B]]
    )(
      implicit F: Monad[F]
    ): Consumer[F, B] =
      Consumer.fromFunction[F, B](handler => self.consume(f(_).flatMap(_.fold(Applicative[F].unit)(handler))))

    /** Similar to [[mapM]], but discards the result of the tapped effect.
      */
    def contraTapM(
      f: A => F[Unit]
    )(
      implicit F: FlatMap[F]
    ): Consumer[F, A] =
      handler => self.consume(a => f(a).flatTap(_ => handler(a)))

    /** For every message, executes provided finalization action that takes the original message as an input. Useful for performing cleanup
      * after the transaction has been completed successfuly. Note that this behaves the same way finalizers would do. The earliest added
      * action is executed the last due to the nature of function composition.
      */
    def afterEach(
      f: A => F[Unit]
    )(
      implicit F: FlatMap[F]
    ): Consumer[F, A] =
      use => self.consume(msg => use(msg) >> f(msg))

    /** For every message, creates an artificial consumer that only handles that one message, and runs it through the given function. This
      * follows `Consumer#flatMap` semantics, so while the consumer of `B` is busy processing, no further messages will be received by
      * `self`.
      */
    def selfProduct[B](
      f: Consumer[F, A] => Consumer[F, B]
    )(
      implicit F: Defer[F]
    ): Consumer[F, (A, B)] =
      self.mproduct(f.compose(Consumer.one[F, A]))

    /** Uses this consumer as a source until completion, then it switches to the second consumer.
      */
    def zip(
      another: Consumer[F, A]
    )(
      implicit F: Apply[F]
    ): Consumer[F, A] =
      handleA => self.consume(handleA) *> another.consume(handleA)

    /** Merges the two consumers by returning one which will run them concurrently, with the same handler. No synchronization between
      * underlying consumers is involved - they will run completely independently.
      */
    def parZip(
      another: Consumer[F, A]
    )(
      implicit F: NonEmptyParallel[F]
    ): Consumer[F, A] =
      handleA => F.parProductR(self.consume(handleA))(another.consume(handleA))

  }

  // For laws. Mortals probably won't have a usecase for this.
  implicit def eq[F[_], A](
    implicit equalFunction: Eq[(A => F[Unit]) => F[Unit]]
  ): Eq[Consumer[F, A]] = equalFunction.narrow

}

// low-priority instances. See Sender companion traits to understand the order they need to be defined in.
sealed trait ConsumerInstances extends ConsumerInstances1 {

  /** The consumer monad.
    *   - `pure` builds a consumer that handles a single message and quits (see [[Consumer.one]])
    *   - `flatMap` has stream-like semantics, see its Scaladoc.
    *
    * Defer is required for stack safety, because we can't guarantee it ourselves for any effect. If you can implement a stack-safe
    * tailRecM, hats off to you.
    */
  implicit def monad[F[_]: Defer]: Monad[Consumer[F, *]] =
    new Monad[Consumer[F, *]] with StackSafeMonad[Consumer[F, *]] with ConsumerApply[F] {

      /** Passes every message to another consumer and waits for it completion, so it behaves like `flatMap` on a stream.
        *
        * Processing a message in the outer consumer will last as long as it takes to deplete the inner consumer. If the inner consumer runs
        * forever, the outer consumer will never see another message.
        */
      def flatMap[A, B](fa: Consumer[F, A])(f: A => Consumer[F, B]): Consumer[F, B] =
        handleB => Defer[F].defer(fa.consume(f(_).consume(handleB)))

      def pure[A](x: A): Consumer[F, A] = Consumer.pure(x)

      override def map[A, B](fa: Consumer[F, A])(f: A => B): Consumer[F, B] =
        super[ConsumerApply].map(fa)(f)

      override def ap[A, B](ff: Consumer[F, A => B])(fa: Consumer[F, A]): Consumer[F, B] =
        super[ConsumerApply].ap(ff)(fa)
    }

  // Note: the semigroup/monoid instances aren't implicit because it's hard to choose a "correct" one.
  // They are mostly here in case somone wants to fold over a list or something, and use one of these instances temporarily.

  /** Sequential semigroup for Consumer. Consumers combined with this will run one after another (first the LHS will be depleted, then the
    * RHS).
    */
  def zipSemigroup[F[_]: Apply, A]: Semigroup[Consumer[F, A]] = _.zip(_)

  /** Sequential monoid for Consumer. Combines just like the sequential semigroup, and `empty` finishes immediately.
    */
  def zipMonoid[F[_]: Applicative, A]: Monoid[Consumer[F, A]] =
    new Monoid[Consumer[F, A]] {
      private val sem = zipSemigroup[F, A]

      def combine(x: Consumer[F, A], y: Consumer[F, A]): Consumer[F, A] = sem.combine(x, y)
      val empty: Consumer[F, A] = Consumer.done
    }

  /** Parallel semigroup for Consumer. Consumers combined with this will run with the same processing function, concurrently.
    */
  def parZipSemigroup[F[_]: NonEmptyParallel, A]: Semigroup[Consumer[F, A]] = _.parZip(_)

  /** Parallel monoid for Consumer. Combines just like the parallel semigroup, and `empty` finishes immediately.
    */
  def parZipMonoid[F[_]: Parallel, A]: Monoid[Consumer[F, A]] =
    new Monoid[Consumer[F, A]] {
      private val sem = parZipSemigroup[F, A]

      def combine(x: Consumer[F, A], y: Consumer[F, A]): Consumer[F, A] = sem.combine(x, y)

      val empty: Consumer[F, A] = Consumer.done(
        using Parallel[F].monad
      )

    }

}

sealed trait ConsumerInstances1 extends ConsumerInstances0 {

  /** Apply instance for Consumer. Most likely you'll never care about using it. For an explanation on how it works, it's preferred to look
    * at flatMap in [[Consumer.monad]] first.
    */
  implicit def applyInstance[F[_]]: Apply[Consumer[F, *]] =
    new ConsumerApply[F] {}

}

sealed trait ConsumerInstances0 {

  /** The consumer functor. See [[Consumer#map]] Scaladoc for more.
    */
  implicit def functor[F[_]]: Functor[Consumer[F, *]] =
    new ConsumerFunctor[F] {}
}

sealed private trait ConsumerApply[F[_]] extends Apply[Consumer[F, *]] with ConsumerFunctor[F] {

  def ap[A, B](ff: Consumer[F, A => B])(fa: Consumer[F, A]): Consumer[F, B] =
    handleB =>
      ff.consume { aToB =>
        fa.consume { a =>
          handleB(aToB(a))
        }
      }

}

sealed private trait ConsumerFunctor[F[_]] extends Functor[Consumer[F, *]] {
  def map[A, B](fa: Consumer[F, A])(f: A => B): Consumer[F, B] = fa.map(f)
}
