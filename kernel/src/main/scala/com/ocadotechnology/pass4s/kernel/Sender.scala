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

import cats.Alternative
import cats.Applicative
import cats.Apply
import cats.Contravariant
import cats.ContravariantMonoidal
import cats.ContravariantSemigroupal
import cats.Eq
import cats.FlatMap
import cats.Foldable
import cats.Functor
import cats.InvariantMonoidal
import cats.Monad
import cats.Monoid
import cats.data.Chain
import cats.data.Const
import cats.data.WriterT
import cats.effect.Sync
import cats.effect.kernel.Ref
import cats.implicits.*
import cats.kernel.Semigroup
import cats.tagless.FunctorK
import cats.~>

/** A sender of messages of type A. That's it, that's the definition.
  */
trait Sender[F[_], -A] extends (A => F[Unit]) with Serializable {
  //
  //
  // Eliminators - see comments in Consumer for an explanation of what that means
  //
  //

  /** Sends a single message.
    */
  def sendOne(msg: A): F[Unit]

  /** Alias for [[sendOne]]. Thanks to this, you can pass a Sender where a function type is expected.
    */
  def apply(msg: A): F[Unit] = sendOne(msg)

  /** An fs2 Pipe that sends every message in the incoming stream. Emits a Unit for every sent message.
    */
  def send: fs2.Pipe[F, A, Unit] = _.evalMap(sendOne)

  /** Like [[send]], but drains the output and doesn't emit anything.
    */
  def send_ : fs2.Pipe[F, A, Nothing] = _.through(send).drain

  //
  //
  // Combinators
  // More methods are available if you import the Cats syntax for contravariant/contravariantSemigroupal etc. - see companion for available instances.
  // Some methods are also available via implicit extensions, as they have extra constraints.
  //
  //

  /** Returns a new sender that applies the given function before passing it to the original Sender.
    */
  def contramap[B](f: B => A): Sender[F, B] = Sender.fromFunction(f.andThen(sendOne))

  /** Alias for [[contramap]].
    */
  def prepare[B](f: B => A): Sender[F, B] = contramap(f)

  /** Returns a sender that, based on whether the input is a Left or a Right, chooses the sender that will receive the message - for Lefts
    * it'll be `this`, for Rights it'll be `another`.
    */
  def or[B](another: Sender[F, B]): Sender[F, Either[A, B]] = Sender.decide(this, another)
}

object Sender extends SenderInstances {

  /** A helper that brings in the implicit Sender[F, A] in scope. The type is more precise than just Sender[F, A], so that it can keep the
    * type of e.g. [[RefSender]].
    */
  def apply[F[_], A](
    implicit S: Sender[F, A]
  ): S.type = S

  /** Helper for defining senders from a function that performs the send.
    */
  def fromFunction[F[_], A](f: A => F[Unit]): Sender[F, A] = f(_)

  /** A sender that ignores all messages.
    */
  def noop[F[_]: InvariantMonoidal]: Sender[F, Any] = fromFunction(_ => InvariantMonoidal[F].unit)

  /** A sender that appends messages to a log in the WriterT monad. You can select the effect and the accumulator. For a special case with
    * Chain as the accumulator, see [[Sender.chainWriter]].
    */
  def writer[F[_]: Applicative, G[_]: Alternative, A]: Sender[WriterT[F, G[A], *], A] =
    fromFunction(a => WriterT.tell[F, G[A]](a.pure[G]))

  /** Special case of [[Sender.writer]] that appends to a Chain.
    */
  def chainWriter[F[_]: Applicative, A]: Sender[WriterT[F, Chain[A], *], A] =
    writer[F, Chain, A]

  /** A sender that appends messages to a log in the Const applicative.
    */
  def const[G[_]: Alternative, A]: Sender[Const[G[A], *], A] =
    fromFunction(a => Const(a.pure[G]))

  /** A Sender for testing, when you don't want a Writer in your effect stack. Returns a more precise type suspended in an effect - for
    * every time you run this effect, you'll get a sender with a separate state.
    *
    * You can get the messages that have been written into the sender by using [[RefSender#sent]].
    */
  def testing[F[_]: Sync, A]: F[RefSender[F, A]] = Ref[F].of(Chain.empty[A]).map(new RefSender[F, A](_))

  /** See [[Sender#or]].
    */
  def decide[F[_], A, B](left: Sender[F, A], right: Sender[F, B]): Sender[F, Either[A, B]] =
    fromFunction(_.fold(left, right))

  /** Creates a sender using a function that builds/chooses one. The main usecase would be having a sender that chooses a destination based
    * on the value of some field in a message.
    */
  def routed[F[_], A](chooseSender: A => Sender[F, A]): Sender[F, A] = fromFunction(msg => chooseSender(msg).sendOne(msg))

  /** Like [[Sender.routed]], but allows specifying the path to the attribute being matched on, separately.
    */
  def routedBy[F[_], A, B](mapMessageToArgument: A => B)(chooseSenderByArgument: B => Sender[F, A]): Sender[F, A] =
    routed(chooseSenderByArgument.compose(mapMessageToArgument))

  /** Syntax enrichments for Sender that can't be implemented directly in the trait due to additional constraints.
    */
  implicit final class SenderOps[F[_], A](private val self: Sender[F, A]) extends AnyVal {

    /** Adds an additional layer of processing before the mesage is sent - for example, logging. The result of `f` is the message that'll be
      * sent using the underlying sender.
      */
    def contramapM[B](
      f: B => F[A]
    )(
      implicit F: FlatMap[F]
    ): Sender[F, B] =
      fromFunction(f(_).flatMap(self.sendOne))

    /** Alias for [[contramapM]].
      */
    def prepareF[B](
      f: B => F[A]
    )(
      implicit F: FlatMap[F]
    ): Sender[F, B] =
      contramapM(f)

    /** Sends all messages in a traversable/foldable instance.
      */
    def sendAll[G[_]: Foldable](
      messages: G[A]
    )(
      implicit F: Applicative[F]
    ): F[Unit] =
      messages.traverse_(self.sendOne)

    /** This can be used together with [[Sender.writer]] or [[Sender.chainWriter]]: After you've sent some messages with a writer sender,
      * you can pass the result to actualSender.sendWritten(...). This will perform the actual send using the underlying sender (`self`).
      *
      * Also see [[sendWrittenK]].
      */
    def sendWritten[Log[_]: Foldable, B](
      result: WriterT[F, Log[A], B]
    )(
      implicit F: Monad[F]
    ): F[B] =
      result.run.flatMap { case (log, result) =>
        self.sendAll(log).as(result)
      }

    /** Like [[sendWritten]], but might be more convenient if you need a [[cats.arrow.FunctionK]]. See demos/examples for how it can be used
      * with a composition of WriterT and a database transaction.
      */
    def sendWrittenK[Log[_]: Foldable](
      implicit F: Monad[F]
    ): WriterT[F, Log[A], *] ~> F =
      new (WriterT[F, Log[A], *] ~> F) {
        def apply[B](fa: WriterT[F, Log[A], B]): F[B] = sendWritten(fa)
      }

    /** Ignores messages that don't pass the filter. If you add a logging middleware on top of this, the messages filtered out might still
      * be seen, but they will never reach the underlying sender.
      */
    def filter(
      f: A => Boolean
    )(
      implicit F: InvariantMonoidal[F]
    ): Sender[F, A] =
      contramapFilter(_.some.filter(f))

    /** Like [[filter]], but allows effects.
      */
    def filterM(
      f: A => F[Boolean]
    )(
      implicit F: Monad[F]
    ): Sender[F, A] =
      contramapFilterM { a =>
        f(a).map(_.guard[Option].as(a))
      }

    /** Like [[filter]], but allows additionally transforming the message in an Option - e.g. for parsing.
      */
    def contramapFilter[B](
      f: B => Option[A]
    )(
      implicit F: InvariantMonoidal[F]
    ): Sender[F, B] =
      fromFunction(f(_).fold(F.unit)(self.sendOne))

    /** Like [[contramapFilter]], but allows an effectful filter.
      */
    def contramapFilterM[B](
      f: B => F[Option[A]]
    )(
      implicit F: Monad[F]
    ): Sender[F, B] =
      fromFunction(f(_).flatMap(_.fold(F.unit)(self.sendOne)))

    /** The dual to [[Sender.or]] - sends both parts of the tuple to the right underlying sender (`self` or `another`, based on the position
      * in the tuple).
      *
      * This is the same as .tupled from Cats syntax.
      */
    def and[B](
      another: Sender[F, B]
    )(
      implicit F: Apply[F]
    ): Sender[F, (A, B)] =
      Sender.fromFunction { case (a, b) =>
        self.sendOne(a) *> another.sendOne(b)
      }

  }

  //
  // instances
  //

  // TODO: Rewrite using the new Scala 3 type lambda syntax when the codebase moves to support Scala 3 source-specific directories:
  // See related discussion: https://github.com/ocadotechnology/pass4s/pull/542#discussion_r2053522966
  //
  implicit def functorK[A]: FunctorK[({ type S[F[_]] = Sender[F, A] })#S] =
    new FunctorK[({ type S[F[_]] = Sender[F, A] })#S] {
      def mapK[F[_], G[_]](af: Sender[F, A])(fk: F ~> G): Sender[G, A] = fromFunction(msg => fk(af.sendOne(msg)))
    }

  // For laws, mostly
  implicit def eq[F[_], A](
    implicit equalFunction: Eq[A => F[Unit]]
  ): Eq[Sender[F, A]] = equalFunction.narrow

}

/** See [[Sender.testing]].
  */
final class RefSender[F[_]: Functor, A] private[kernel] (log: Ref[F, Chain[A]]) extends Sender[F, A] {

  /** Returns a list of all messages sent by this sender so far. Note: it doesn't clear the log.
    */
  val sent: F[List[A]] = log.get.map(_.toList)

  /** Clears the log.
    */
  val clear: F[Unit] = log.set(Chain.empty)

  def sendOne(msg: A): F[Unit] = log.update(_.append(msg))
}

// Priority traits top to bottom: more specific to more generic
// Each more specific trait needs to extend the one below.
// The approach is inspired by Kleisli from cats.
// Note: In 2.13, these can all probably be in a single trait without a cake pattern.
sealed trait SenderInstances extends SenderInstances0 {

  /** This instance can be used for things like `contramap` and `contramapN`.
    *
    * @example
    *   {{{
    *
    * case class User( age: Int, name: String )
    *
    * def demo(ageSender: Sender[IO, Int], nameSender: Sender[IO, String]): Sender[IO, User] = (ageSender, nameSender).contramapN(u =>
    * (u.age, u.name))
    *
    *   }}}
    */
  implicit def contravariantMonoidal[F[_]: Applicative]: ContravariantMonoidal[Sender[F, *]] =
    new ContravariantMonoidal[Sender[F, *]] with SenderContravariantSemigroupal[F] {
      val F: Apply[F] = implicitly
      val unit: Sender[F, Unit] = Sender.noop
    }

  /** Combines two senders by passing the message to each of them. `empty` ignores the message.
    */
  implicit def monoid[F[_]: Applicative, A]: Monoid[Sender[F, A]] =
    ContravariantMonoidal.monoid

}

sealed trait SenderInstances0 extends SenderInstances1 {

  implicit def contravariantSemigroupal[F[_]: Apply]: ContravariantSemigroupal[Sender[F, *]] =
    new SenderContravariantSemigroupal[F] {
      val F: Apply[F] = implicitly
    }

  /** Combines two senders by passing the message to each of them.
    */
  implicit def semigroup[F[_]: Apply, A]: Semigroup[Sender[F, A]] =
    ContravariantSemigroupal.semigroup
}

sealed trait SenderInstances1 {
  implicit def contravariant[F[_]]: Contravariant[Sender[F, *]] =
    new SenderContravariant[F] {}
}

sealed private trait SenderContravariantSemigroupal[F[_]] extends ContravariantSemigroupal[Sender[F, *]] with SenderContravariant[F] {
  implicit def F: Apply[F]

  def product[A, B](fa: Sender[F, A], fb: Sender[F, B]): Sender[F, (A, B)] = fa.and(fb)
}

sealed private trait SenderContravariant[F[_]] extends Contravariant[Sender[F, *]] {
  def contramap[A, B](fa: Sender[F, A])(f: B => A): Sender[F, B] = fa.contramap(f)
}
