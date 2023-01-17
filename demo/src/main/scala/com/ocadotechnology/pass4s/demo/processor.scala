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

package com.ocadotechnology.pass4s.demo

import scala.concurrent.duration._

import cats.effect.kernel.Temporal
import cats.implicits._
import com.ocadotechnology.pass4s.kernel._

object MyProcessor {

  def instance[F[_]: MyService: Temporal](
    implicit intSender: Sender[F, Int],
    boolSender: Sender[F, Boolean]
  ): String => F[Unit] =
    msg =>
      Sender[F, Boolean].sendOne(true) *>
        Sender[F, Int].sendOne(msg.length()) *>
        MyService[F].foo() *> Temporal[F].sleep(1.second)

}
