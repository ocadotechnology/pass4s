package com.ocadotechnology.pass4s.demo

import cats.~>
import com.ocadotechnology.pass4s.kernel._

trait MyService[F[_]] {
  def foo(): F[Unit]

  def mapK[G[_]](fk: F ~> G): MyService[G] =
    () => fk(foo())

}

object MyService {
  def apply[F[_]](implicit F: MyService[F]): F.type = F
  def instance[F[_]](implicit sender: Sender[F, Int]): MyService[F] = () => sender.sendOne(42)
}
