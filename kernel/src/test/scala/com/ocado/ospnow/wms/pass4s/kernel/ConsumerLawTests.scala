package com.ocado.ospnow.wms.pass4s.kernel

import cats.Eval
import cats.data.EitherT
import cats.data.WriterT
import cats.implicits._
import cats.kernel.Monoid
import cats.kernel.laws.discipline.EqTests
import cats.kernel.laws.discipline.MonoidTests
import cats.kernel.laws.discipline.SemigroupTests
import cats.laws.discipline.ExhaustiveCheck
import cats.laws.discipline.FunctorFilterTests
import cats.laws.discipline.MiniInt
import cats.laws.discipline.MonadTests
import cats.laws.discipline.arbitrary._
import cats.laws.discipline.eq._
import com.ocado.ospnow.wms.pass4s.kernel.Arbitraries._
import com.ocado.ospnow.wms.pass4s.kernel.Cogens._
import org.scalacheck.Arbitrary
import org.scalacheck.Cogen
import weaver.FunSuite
import weaver.discipline.Discipline

object ConsumerLawTests extends FunSuite with Discipline {


  // This is kinda hacky, as we only verify it's correct for a subset of possible values,
  // but using real equalities for higher-order functions involves generating absurdly gigantic amounts of functions.
  // Even choosing 100 random integers is more realistic than that.
  implicit def exhaustiveFunctionByExamples[M[_], A: Cogen](
    implicit arb: Arbitrary[M[Unit]]
  ): ExhaustiveCheck[A => M[Unit]] =
    ExhaustiveCheck.instance {
      List.fill(100)(Arbitrary.arbitrary[A => M[Unit]].sample).flattenOption
    }

  type F[A] = WriterT[EitherT[Eval, Boolean, *], MiniInt, A]

  implicit val miniIntMonoid: Monoid[MiniInt] = MiniInt.miniIntAddition

  checkAll(
    "Eq[Consumer[F, Option[Boolean]]]",
    EqTests[Consumer[F, Option[Boolean]]].eqv
  )

  checkAll(
    "Monad[Consumer[F, *]]",
    MonadTests[Consumer[F, *]].monad[Boolean, Boolean, Boolean]
  )

  checkAll(
    "FunctorFiler[Consumer[F, *]]",
    FunctorFilterTests[Consumer[F, *]].functorFilter[Boolean, Boolean, Boolean]
  )

  checkAll(
    "sequential Monoid[Consumer[F, Int]]",
    MonoidTests[Consumer[F, Int]](Consumer.zipMonoid).monoid
  )

  checkAll(
    "parallel Semigroup[Consumer[List, Int]]",
    SemigroupTests[Consumer[List, Int]](Consumer.parZipSemigroup).semigroup
  )

  // This one is commented out as there's a bug in CE3 that needs fixing: https://github.com/typelevel/cats-effect/issues/2778
  // checkAll(
  //   "Monoid[Consumer[IO, Int]]",
  //   MonoidTests[Consumer[IO, Int]](Consumer.zipMonoid[IO, Int]).monoid
  // )

}
