package com.ocado.ospnow.wms.pass4s.kernel

import org.scalacheck.Arbitrary
import org.scalacheck.Gen

object Arbitraries {

  implicit def arbitrarySenderFromFunction[F[_], A](
    implicit arb: Arbitrary[A => F[Unit]]
  ): Arbitrary[Sender[F, A]] =
    Arbitrary(Gen.resultOf(Sender.fromFunction[F, A] _))

  implicit def arbitraryConsumerFromFunction[F[_], A](
    implicit arb: Arbitrary[(A => F[Unit]) => F[Unit]]
  ): Arbitrary[Consumer[F, A]] =
    Arbitrary(Gen.resultOf(Consumer.fromFunction[F, A] _))

}
