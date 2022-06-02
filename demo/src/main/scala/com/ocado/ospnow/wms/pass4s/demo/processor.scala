package com.ocado.ospnow.wms.pass4s.demo

import scala.concurrent.duration._

import cats.effect.kernel.Temporal
import cats.implicits._
import com.ocado.ospnow.wms.pass4s.kernel._

object MyProcessor {

  // this is pure logic, the only "tap" thing here are Senders,
  // which are actually from the algebraic package and have zero dependencies on anything else in the library.
  // They can also be created easily with Sender.writer, Sender.testing, Sender.noop etc.
  def instance[F[_]: MyService: Temporal](
    implicit intSender: Sender[F, Int],
    boolSender: Sender[F, Boolean]
  ): String => F[Unit] =
    msg =>
      Sender[F, Boolean].sendOne(true) *>
        Sender[F, Int].sendOne(msg.length()) *>
        MyService[F].foo() *> Temporal[F].sleep(1.second)

}
