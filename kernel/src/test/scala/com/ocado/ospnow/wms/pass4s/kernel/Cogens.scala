package com.ocado.ospnow.wms.pass4s.kernel

import org.scalacheck.Cogen

object Cogens {

  implicit def cogenSender[F[_], A](implicit cogenFunction: Cogen[A => F[Unit]]): Cogen[Sender[F, A]] =
    cogenFunction
      .contramap(_.sendOne)

  implicit def cogenConsumer[F[_], A](implicit cogenFunction: Cogen[(A => F[Unit]) => F[Unit]]): Cogen[Consumer[F, A]] =
    cogenFunction
      .contramap(_.consume)

}
