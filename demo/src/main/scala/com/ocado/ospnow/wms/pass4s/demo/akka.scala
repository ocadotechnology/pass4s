package com.ocado.ospnow.wms.pass4s.demo

import akka.actor.ActorSystem
import cats.effect.Async

import cats.effect.Resource
import cats.effect.Sync
import cats.implicits._

object Akka {
  def system[F[_]: Async]: Resource[F, ActorSystem] =
    Resource.make(Sync[F].delay(ActorSystem()))(sys => Async[F].fromFuture(Sync[F].delay(sys.terminate())).void)
}
