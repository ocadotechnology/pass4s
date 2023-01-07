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

package com.ocadotechnology.pass4s.high

import cats.effect._
import cats.implicits._
import com.ocadotechnology.pass4s.core.Destination
import com.ocadotechnology.pass4s.core.Message.Payload
import com.ocadotechnology.pass4s.core.Message
import com.ocadotechnology.pass4s.core.Source
import com.ocadotechnology.pass4s.kernel.Consumer
import com.ocadotechnology.pass4s.kernel.RefSender
import com.ocadotechnology.pass4s.kernel.Sender
import weaver.SimpleIOSuite

import scala.reflect.runtime.universe._

object BrokerTests extends SimpleIOSuite {

  trait Foo
  trait Bar
  trait Baz

  final case class FooDestination(name: String) extends Source[Foo] with Destination[Foo] {
    override val capability: Type = typeOf[Foo]
  }

  final case class BarDestination(name: String) extends Source[Bar] {
    override val capability: Type = typeOf[Bar]
  }

  final case class BazDestination(name: String) extends Destination[Baz] {
    override val capability: Type = typeOf[Baz]
  }

  val fooDestination: FooDestination = FooDestination("Foo")
  val barDestination: BarDestination = BarDestination("Bar")
  val bazDestination: BazDestination = BazDestination("Baz")
  val foo1Destination: FooDestination = FooDestination("Foo1")
  val foo2Destination: FooDestination = FooDestination("Foo2")

  val fooId = "foo"
  val barId = "bar"
  val bazId = "baz"
  val foo1Id = "foo1"
  val foo2Id = "foo2"

  val somePayload: Payload = payload("message")

  test("routed broker should select correct consumer") {
    for {
      (foo1Broker, _)  <- createBroker[Foo](foo1Id)
      (foo2Broker, _)  <- createBroker[Foo](foo2Id)
      fooBroker = Broker.routed[IO, Foo] {
                    case this.foo1Destination => foo1Broker
                    case _                    => foo2Broker
                  }
      consumedFromFoo1 <- consumeOne(fooBroker.consumer(foo1Destination))
      consumedFromFoo2 <- consumeOne(fooBroker.consumer(foo2Destination))
    } yield expect.all(
      consumedFromFoo1.text == foo1Id,
      consumedFromFoo2.text == foo2Id
    )
  }

  test("routed broker should select correct sender") {
    for {
      (foo1Broker, foo1RefSender) <- createBroker[Foo](foo1Id)
      (foo2Broker, foo2RefSender) <- createBroker[Foo](foo2Id)
      fooBroker = Broker.routed[IO, Foo] {
                    case this.foo1Destination => foo1Broker
                    case _                    => foo2Broker
                  }
      sentToFoo1                  <- fooBroker.sender.sendOne(Message(somePayload, foo1Destination)) *> foo1RefSender.sent
      sentToFoo2                  <- fooBroker.sender.sendOne(Message(somePayload, foo2Destination)) *> foo2RefSender.sent
    } yield expect.all(
      sentToFoo1 == List((foo1Id, somePayload)),
      sentToFoo2 == List((foo2Id, somePayload))
    )
  }

  test("merged broker should select correct consumer") {
    for {
      (fooBroker, _)  <- createBroker[Foo](fooId)
      (barBroker, _)  <- createBroker[Bar](barId)
      (bazBroker, _)  <- createBroker[Baz](bazId)
      broker          <- Broker.mergeByCapabilities(fooBroker, barBroker, bazBroker)
      consumedFromFoo <- consumeOne(broker.consumer(fooDestination))
      consumedFromBar <- consumeOne(broker.consumer(barDestination))
    } yield expect.all(
      consumedFromFoo.text == fooId,
      consumedFromBar.text == barId
    )
  }

  test("merged broker should select correct sender") {
    for {
      (fooBroker, fooRefSender) <- createBroker[Foo](fooId)
      (barBroker, _)            <- createBroker[Bar](barId)
      (bazBroker, bazRefSender) <- createBroker[Baz](bazId)
      broker                    <- Broker.mergeByCapabilities(fooBroker, barBroker, bazBroker)
      sentToFoo                 <- broker.sender.sendOne(Message(somePayload, fooDestination)) *> fooRefSender.sent
      sentToBaz                 <- broker.sender.sendOne(Message(somePayload, bazDestination)) *> bazRefSender.sent
    } yield expect.all(
      sentToFoo == List((fooId, somePayload)),
      sentToBaz == List((bazId, somePayload))
    )
  }

  test("merging should fail when capabilities have common capability") {
    for {
      (foo1Broker, _) <- createBroker[Foo](foo1Id)
      (foo2Broker, _) <- createBroker[Foo](foo2Id)
      (barBroker, _)  <- createBroker[Bar](barId)
      (bazBroker, _)  <- createBroker[Baz](bazId)
      foo1BarBroker   <- Broker.mergeByCapabilities(foo1Broker, barBroker)
      foo2BazBroker   <- Broker.mergeByCapabilities(foo2Broker, bazBroker)
      result          <- Broker.mergeByCapabilities(foo1BarBroker, foo2BazBroker).attempt
    } yield {
      val ex = result.swap.getOrElse(throw new IllegalStateException())
      expect.all(
        ex.isInstanceOf[IllegalArgumentException],
        ex.getMessage.contains("common capability")
      )
    }
  }

  test("both routed and merged broker should select correct consumer") {
    for {
      (foo1Broker, _)  <- createBroker[Foo](foo1Id)
      (foo2Broker, _)  <- createBroker[Foo](foo2Id)
      fooBroker = Broker.routed[IO, Foo] {
                    case this.foo1Destination => foo1Broker
                    case _                    => foo2Broker
                  }
      (barBroker, _)   <- createBroker[Bar](barId)
      (bazBroker, _)   <- createBroker[Baz](bazId)
      broker           <- Broker.mergeByCapabilities(fooBroker, barBroker, bazBroker)
      consumedFromFoo1 <- consumeOne(broker.consumer(foo1Destination))
      consumedFromFoo2 <- consumeOne(broker.consumer(foo2Destination))
      consumedFromBar  <- consumeOne(broker.consumer(barDestination))
    } yield expect.all(
      consumedFromFoo1.text == foo1Id,
      consumedFromFoo2.text == foo2Id,
      consumedFromBar.text == barId
    )
  }

  test("both routed and merged broker should select correct sender") {
    for {
      (foo1Broker, foo1RefSender) <- createBroker[Foo](foo1Id)
      (foo2Broker, foo2RefSender) <- createBroker[Foo](foo2Id)
      fooBroker = Broker.routed[IO, Foo] {
                    case this.foo1Destination => foo1Broker
                    case _                    => foo2Broker
                  }
      (barBroker, _)              <- createBroker[Bar](barId)
      (bazBroker, bazRefSender)   <- createBroker[Baz](bazId)
      broker                      <- Broker.mergeByCapabilities(fooBroker, barBroker, bazBroker)
      sentToFoo1                  <- fooBroker.sender.sendOne(Message(somePayload, foo1Destination)) *> foo1RefSender.sent
      sentToFoo2                  <- fooBroker.sender.sendOne(Message(somePayload, foo2Destination)) *> foo2RefSender.sent
      sentToBaz                   <- broker.sender.sendOne(Message(somePayload, bazDestination)) *> bazRefSender.sent
    } yield expect.all(
      sentToFoo1 == List((foo1Id, somePayload)),
      sentToFoo2 == List((foo2Id, somePayload)),
      sentToBaz == List((bazId, somePayload))
    )
  }

  private def createBroker[P](id: String): IO[(Broker[IO, P], RefSender[IO, (String, Payload)])] =
    Sender.testing[IO, (String, Payload)].fproductLeft { refSender =>
      new Broker[IO, P] {
        override def consumer[R >: P](source: Source[R]): Consumer[IO, Payload] =
          Consumer.one(payload(id))

        override def sender[R >: P]: Sender[IO, Message[R]] =
          refSender.contramap[Message[R]](m => (id, m.payload))
      }
    }

  private def consumeOne[A](consumer: Consumer[IO, A]): IO[A] =
    for {
      deferred <- Deferred[IO, A]
      result   <- IO.race(consumer.consume(deferred.complete(_).void) *> deferred.get, deferred.get)
    } yield result.merge

  private def payload(text: String): Payload = Payload(text, Map())

}
