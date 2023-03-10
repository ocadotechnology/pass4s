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

import cats.tagless.FunctorK
import cats.Contravariant
import cats.Apply
import cats.kernel.Semigroup
import cats.kernel.Eq
import cats.ContravariantSemigroupal
import cats.kernel.Monoid
import cats.Applicative
import cats.ContravariantMonoidal
import cats.tagless.InvariantK
import cats.Functor
import cats.FunctorFilter
import cats.InvariantMonoidal
import cats.Defer
import cats.Monad

//Compilation tests for instance derivations
object ImplicitPriorityTests {

  class senderLevel0 {

    def functorK[A]: FunctorK[Sender[*[_], A]] = implicitly
    def contravariant[F[_]]: Contravariant[Sender[F, *]] = implicitly
    def semigroup[F[_]: Apply, A]: Semigroup[Sender[F, A]] = implicitly
    def eq[F[_], A](implicit equalFunction: Eq[A => F[Unit]]): Eq[Sender[F, A]] = implicitly
  }

  class senderLevel1 {
    def contravariantSemigroupal[F[_]: Apply]: ContravariantSemigroupal[Sender[F, *]] = implicitly
    def monoid[F[_]: Applicative, A]: Monoid[Sender[F, A]] = implicitly
  }

  class senderLevel2 {
    def contravariantMonoidal[F[_]: Applicative]: ContravariantMonoidal[Sender[F, *]] =
      implicitly
  }

  class consumerLevel0 {
    def invariantK[A]: InvariantK[Consumer[*[_], A]] = implicitly
    def functor[F[_]]: Functor[Consumer[F, *]] = implicitly
    def functorFilter[F[_]: InvariantMonoidal]: FunctorFilter[Consumer[F, *]] = implicitly
    def eq[F[_], A](implicit equalFunction: Eq[(A => F[Unit]) => F[Unit]]): Eq[Consumer[F, A]] = implicitly
  }

  class consumerLevel1 {
    def monad[F[_]: Defer]: Monad[Consumer[F, *]] = implicitly
  }

}
