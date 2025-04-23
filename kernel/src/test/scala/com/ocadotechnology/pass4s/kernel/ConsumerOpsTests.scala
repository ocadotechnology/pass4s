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

import cats.data.Kleisli
import cats.effect.IO
import cats.implicits.*
import cats.~>
import weaver.SimpleMutableIOSuite
import weaver.scalacheck.Checkers
import cats.effect.Resource
import cats.Show
import cats.effect.kernel.Ref
import cats.data.Chain

object ConsumerOpsTests extends SimpleMutableIOSuite with Checkers {
  type TransactionalIO[A] = Kleisli[IO, Int, A]
  val transact: TransactionalIO ~> IO = Kleisli.applyK(42)

  test("Consumer#consumeCommitK") {
    for {
      sender <- Sender.testing[IO, Int]
      _      <- Consumer.one[IO, Int](10).consumeCommitK(transact) { msg =>
                  Kleisli.liftK[IO, Int](sender.sendOne(msg)) *>
                    Kleisli(sender.sendOne)
                }
      sent   <- sender.sent
    } yield assert(
      sent == List(10, 42)
    )
  }

  test("Consumer#evalMapFilter") {
    for {
      consumed     <- Sender.testing[IO, Int]
      ignored      <- Sender.testing[IO, Int]
      _            <- Consumer
                        .many[IO, Int]((0 until 6)*)
                        .evalMapFilter {
                          case i if i % 2 === 0 => IO.pure(Some(i))
                          case i                => ignored.sendOne(i).as(None)
                        }
                        .forward(consumed)
      consumedSent <- consumed.sent
      ignoredSent  <- ignored.sent
    } yield assert.all(
      consumedSent == List(0, 2, 4),
      ignoredSent == List(1, 3, 5)
    )
  }

  test("Consumer#surroundEachWith") {
    for {
      sender <- Sender.testing[IO, Int]
      _      <- Consumer
                  .many[IO, Int](1, 2, 4)
                  .surroundEachWith { msg => handle =>
                    sender.sendOne(msg - 1) *>
                      handle *>
                      sender.sendOne(msg + 1)
                  }
                  .forward(sender)
      sent   <- sender.sent
    } yield assert(
      sent == List(List(0, 1, 2), List(1, 2, 3), List(3, 4, 5)).flatten
    )
  }

  test("Consumer#afterEach") {
    sealed trait Message extends Product with Serializable
    final case class Result[A](value: A) extends Message
    final case class PostProcessing(msg: String) extends Message

    for {
      state      <- Ref[IO].of(Chain[Message]())
      _          <- Consumer
                      .many[IO, Int](1, 2)
                      .afterEach(msg => state.update(_ append PostProcessing(s"First post process $msg")))
                      .afterEach(msg => state.update(_ append PostProcessing(s"Second post process $msg")))
                      .mapM(result => state.update(_ append Result(result)))
                      .apply(_ => IO.pure(()))
      finalState <- state.get
    } yield assert(
      finalState == Chain(
        Result(1),
        PostProcessing("Second post process 1"),
        PostProcessing("First post process 1"),
        Result(2),
        PostProcessing("Second post process 2"),
        PostProcessing("First post process 2")
      )
    )
  }

  test("Consumer#surroundAll") {
    for {
      sender <- Sender.testing[IO, Int]
      _      <- Consumer
                  .many[IO, Int](1, 2, 4)
                  .surroundAll { handle =>
                    sender.sendOne(0) *>
                      handle *>
                      sender.sendOne(10)
                  }
                  .forward(sender)
      sent   <- sender.sent
    } yield assert(
      sent == List(0, 1, 2, 4, 10)
    )
  }

  test("Consumer#sequential") {
    implicit def showAnyFunction[A, B]: Show[A => B] = Show.fromToString

    forall {
      (
        open: Int => Int,
        close: Int => Int,
        mapValid: Int => Int,
        messages: List[Int],
        isValid: Int => Boolean
      ) =>
        def message(
          i: Int
        )(
          implicit sender: Sender[IO, Int]
        ): Resource[IO, Int] =
          Resource.make(
            sender.contramap(open).sendOne(i).as(i)
          )(sender.contramap(close).sendOne)

        for {
          sender <- Sender.testing[IO, Int]
          _      <- {
            implicit val s = sender

            Consumer
              .sequential()(
                fs2.Stream.emits(messages.map(message))
              )
              .consume {
                case msg if isValid(msg) => sender.contramap(mapValid).sendOne(msg)
                case _                   => IO.raiseError(new Throwable("fail"))
              }
          }
          sent   <- sender.sent
        } yield {
          val validFunctions = List(open, mapValid, close).mapApply(_: Int)
          val invalidFunctions = List(open, close).mapApply(_: Int)

          val expectedMessages = messages.flatMap {
            case msg if isValid(msg) => validFunctions(msg)
            case msg                 => invalidFunctions(msg)
          }

          assert(
            sent == expectedMessages
          )
        }
    }
  }

}
