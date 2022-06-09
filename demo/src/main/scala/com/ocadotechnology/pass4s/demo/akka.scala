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

package com.ocadotechnology.pass4s.demo

import akka.actor.ActorSystem
import cats.effect.Async

import cats.effect.Resource
import cats.effect.Sync
import cats.implicits._

object Akka {
  def system[F[_]: Async]: Resource[F, ActorSystem] =
    Resource.make(Sync[F].delay(ActorSystem()))(sys => Async[F].fromFuture(Sync[F].delay(sys.terminate())).void)
}
