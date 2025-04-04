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

import cats.effect.*
import cats.implicits.*
import com.ocadotechnology.pass4s.core.Destination
import com.ocadotechnology.pass4s.core.Message.Payload
import com.ocadotechnology.pass4s.core.Message
import com.ocadotechnology.pass4s.core.Source
import com.ocadotechnology.pass4s.kernel.Consumer
import com.ocadotechnology.pass4s.kernel.RefSender
import com.ocadotechnology.pass4s.kernel.Sender
import izumi.reflect.Tag
import izumi.reflect.macrortti.LightTypeTag
import weaver.SimpleIOSuite

object BrokerTests extends SimpleIOSuite {

  trait Foo
  trait Bar
  trait Baz
  trait Qux

  type Intersection1 = Foo & Bar
  type Intersection2 = Baz & Qux
  type Intersection3 = Bar & Baz

  final case class FooDestination(name: String) extends Source[Foo] with Destination[Foo] {
    override val capability: LightTypeTag = Tag[Foo].tag
  }

  final case class BarDestination(name: String) extends Source[Bar] {
    override val capability: LightTypeTag = Tag[Bar].tag
  }

  final case class BazDestination(name: String) extends Destination[Baz] {
    override val capability: LightTypeTag = Tag[Baz].tag
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
      createdBroker1   <- createBroker[Foo](foo1Id)
      createdBroker2   <- createBroker[Foo](foo2Id)
      fooBroker = Broker.routed[IO, Foo] {
                    case this.foo1Destination => createdBroker1._1
                    case _                    => createdBroker2._1
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
      foo1BrokerSenderPair <- createBroker[Foo](foo1Id)
      (foo1Broker, foo1RefSender) = foo1BrokerSenderPair
      foo2BrokerSenderPair <- createBroker[Foo](foo2Id)
      (foo2Broker, foo2RefSender) = foo2BrokerSenderPair
      fooBroker = Broker.routed[IO, Foo] {
                    case this.foo1Destination => foo1Broker
                    case _                    => foo2Broker
                  }
      sentToFoo1           <- fooBroker.sender.sendOne(Message(somePayload, foo1Destination)) *> foo1RefSender.sent
      sentToFoo2           <- fooBroker.sender.sendOne(Message(somePayload, foo2Destination)) *> foo2RefSender.sent
    } yield expect.all(
      sentToFoo1 == List((foo1Id, somePayload)),
      sentToFoo2 == List((foo2Id, somePayload))
    )
  }

  test("merged broker should select correct consumer") {
    for {
      fooBrokerSenderPair <- createBroker[Foo](fooId)
      (fooBroker, _) = fooBrokerSenderPair
      barBrokerSenderPair <- createBroker[Bar](barId)
      (barBroker, _) = barBrokerSenderPair
      bazBrokerSenderPair <- createBroker[Baz](bazId)
      (bazBroker, _) = bazBrokerSenderPair
      broker              <- Broker.mergeByCapabilities(fooBroker, barBroker, bazBroker)
      consumedFromFoo     <- consumeOne(broker.consumer(fooDestination))
      consumedFromBar     <- consumeOne(broker.consumer(barDestination))
    } yield expect.all(
      consumedFromFoo.text == fooId,
      consumedFromBar.text == barId
    )
  }

  test("merged broker should select correct sender") {
    for {
      fooBrokerSenderPair <- createBroker[Foo](fooId)
      (fooBroker, fooRefSender) = fooBrokerSenderPair
      barBrokerSenderPair <- createBroker[Bar](barId)
      (barBroker, _) = barBrokerSenderPair
      bazBrokerSenderPair <- createBroker[Baz](bazId)
      (bazBroker, bazRefSender) = bazBrokerSenderPair
      broker              <- Broker.mergeByCapabilities(fooBroker, barBroker, bazBroker)
      sentToFoo           <- broker.sender.sendOne(Message(somePayload, fooDestination)) *> fooRefSender.sent
      sentToBaz           <- broker.sender.sendOne(Message(somePayload, bazDestination)) *> bazRefSender.sent
    } yield expect.all(
      sentToFoo == List((fooId, somePayload)),
      sentToBaz == List((bazId, somePayload))
    )
  }

  test("merging should fail when capabilities have common capability") {
    for {
      foo1BrokerSenderPair <- createBroker[Foo](foo1Id)
      (foo1Broker, _) = foo1BrokerSenderPair
      foo2BrokerSenderPair <- createBroker[Foo](foo2Id)
      (foo2Broker, _) = foo2BrokerSenderPair
      barBrokerSenderPair  <- createBroker[Bar](barId)
      (barBroker, _) = barBrokerSenderPair
      bazBrokerSenderPair  <- createBroker[Baz](bazId)
      (bazBroker, _) = bazBrokerSenderPair
      foo1BarBroker        <- Broker.mergeByCapabilities(foo1Broker, barBroker)
      foo2BazBroker        <- Broker.mergeByCapabilities(foo2Broker, bazBroker)
      result               <- Broker.mergeByCapabilities(foo1BarBroker, foo2BazBroker).attempt
    } yield {
      val ex = result.swap.getOrElse(throw new IllegalStateException())
      expect.all(
        ex.isInstanceOf[IllegalArgumentException],
        ex.getMessage.contains("common capability")
      )
    }
  }

  test("merging should fail when there's a common capability in an intersection type") {
    for {
      brokerSenderPair1 <- createBroker[Intersection1](foo1Id)
      (broker1, _) = brokerSenderPair1
      brokerSenderPair2 <- createBroker[Intersection3](barId)
      (broker2, _) = brokerSenderPair2
      result            <- Broker.mergeByCapabilities(broker1, broker2).attempt
    } yield {
      val ex = result.swap.getOrElse(throw new IllegalStateException())
      expect.all(
        ex.isInstanceOf[IllegalArgumentException],
        ex.getMessage.contains("common capability")
      )
    }
  }

  test("merging should succeed when there's a common capability in an intersection type") {
    for {
      brokerSenderPair1 <- createBroker[Intersection1](fooId)
      (broker1, sender1) = brokerSenderPair1
      brokerSenderPair2 <- createBroker[Intersection2](bazId)
      (broker2, sender2) = brokerSenderPair2
      broker            <- Broker.mergeByCapabilities(broker1, broker2)
      sentToFoo         <- broker.sender.sendOne(Message(somePayload, fooDestination)) *> sender1.sent
      sentToBaz         <- broker.sender.sendOne(Message(somePayload, bazDestination)) *> sender2.sent
    } yield expect.all(
      sentToFoo == List((fooId, somePayload)),
      sentToBaz == List((bazId, somePayload))
    )
  }

  test("both routed and merged broker should select correct consumer") {
    for {
      foo1BrokerSenderPair <- createBroker[Foo](foo1Id)
      (foo1Broker, _) = foo1BrokerSenderPair
      foo2BrokerSenderPair <- createBroker[Foo](foo2Id)
      (foo2Broker, _) = foo2BrokerSenderPair
      fooBroker = Broker.routed[IO, Foo] {
                    case this.foo1Destination => foo1Broker
                    case _                    => foo2Broker
                  }
      barBrokerSenderPair  <- createBroker[Bar](barId)
      (barBroker, _) = barBrokerSenderPair
      bazBrokerSenderPair  <- createBroker[Baz](bazId)
      (bazBroker, _) = bazBrokerSenderPair
      broker               <- Broker.mergeByCapabilities(fooBroker, barBroker, bazBroker)
      consumedFromFoo1     <- consumeOne(broker.consumer(foo1Destination))
      consumedFromFoo2     <- consumeOne(broker.consumer(foo2Destination))
      consumedFromBar      <- consumeOne(broker.consumer(barDestination))
    } yield expect.all(
      consumedFromFoo1.text == foo1Id,
      consumedFromFoo2.text == foo2Id,
      consumedFromBar.text == barId
    )
  }

  test("both routed and merged broker should select correct sender") {
    for {
      foo1BrokerSenderPair <- createBroker[Foo](foo1Id)
      (foo1Broker, foo1RefSender) = foo1BrokerSenderPair
      foo2BrokerSenderPair <- createBroker[Foo](foo2Id)
      (foo2Broker, foo2RefSender) = foo2BrokerSenderPair
      fooBroker = Broker.routed[IO, Foo] {
                    case this.foo1Destination => foo1Broker
                    case _                    => foo2Broker
                  }
      barBrokerSenderPair  <- createBroker[Bar](barId)
      (barBroker, _) = barBrokerSenderPair
      bazBrokerSenderPair  <- createBroker[Baz](bazId)
      (bazBroker, bazRefSender) = bazBrokerSenderPair
      broker               <- Broker.mergeByCapabilities(fooBroker, barBroker, bazBroker)
      sentToFoo1           <- fooBroker.sender.sendOne(Message(somePayload, foo1Destination)) *> foo1RefSender.sent
      sentToFoo2           <- fooBroker.sender.sendOne(Message(somePayload, foo2Destination)) *> foo2RefSender.sent
      sentToBaz            <- broker.sender.sendOne(Message(somePayload, bazDestination)) *> bazRefSender.sent
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
