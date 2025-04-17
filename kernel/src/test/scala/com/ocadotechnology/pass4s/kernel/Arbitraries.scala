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

import org.scalacheck.Arbitrary
import org.scalacheck.Gen

object Arbitraries {

  implicit def arbitrarySenderFromFunction[F[_], A](
    implicit arb: Arbitrary[A => F[Unit]]
  ): Arbitrary[Sender[F, A]] =
    Arbitrary(Gen.resultOf(Sender.fromFunction[F, A]))

  implicit def arbitraryConsumerFromFunction[F[_], A](
    implicit arb: Arbitrary[(A => F[Unit]) => F[Unit]]
  ): Arbitrary[Consumer[F, A]] =
    Arbitrary(Gen.resultOf(Consumer.fromFunction[F, A]))

}
