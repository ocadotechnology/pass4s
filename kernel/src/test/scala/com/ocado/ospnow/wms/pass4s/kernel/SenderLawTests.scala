/*
 * Copyright 2022 Ocado Technology
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

import cats.data.Writer
import cats.kernel.laws.discipline.EqTests
import cats.kernel.laws.discipline.MonoidTests
import cats.laws.discipline.ContravariantMonoidalTests
import cats.laws.discipline.MiniInt
import cats.laws.discipline.arbitrary._
import cats.laws.discipline.eq._
import com.ocadotechnology.pass4s.kernel.Arbitraries._
import com.ocadotechnology.pass4s.kernel.Cogens._
import org.scalacheck.Arbitrary
import weaver.FunSuite
import weaver.discipline.Discipline

object SenderLawTests extends FunSuite with Discipline {

  checkAll(
    "ContravariantMonoidal[Sender[Writer[String, *], *]]",
    ContravariantMonoidalTests[Sender[Writer[String, *], *]].contravariantMonoidal[MiniInt, MiniInt, MiniInt]
  )

  checkAll(
    "Monoid[Sender[Writer[String, *], MiniInt]]",
    MonoidTests[Sender[Writer[String, *], MiniInt]].monoid
  )

  // Thanks scala - the parameters need to be written by hand as of 2.12.12.
  checkAll(
    "Eq[Sender[Writer[String, *], MiniInt]]",
    EqTests[Sender[Writer[String, *], MiniInt]].eqv(
      arbitrarySenderFromFunction,
      Arbitrary.arbFunction1(
        arbitrarySenderFromFunction,
        cogenSender
      )
    )
  )
}
