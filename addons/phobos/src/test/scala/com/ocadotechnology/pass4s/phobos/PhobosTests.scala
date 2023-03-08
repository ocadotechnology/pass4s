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

package com.ocadotechnology.pass4s.phobos

import cats.effect.IO
import com.ocadotechnology.pass4s.core.Destination
import com.ocadotechnology.pass4s.core.Message
import com.ocadotechnology.pass4s.core.Message.Payload
import com.ocadotechnology.pass4s.kernel.Consumer
import com.ocadotechnology.pass4s.kernel.Sender
import com.ocadotechnology.pass4s.phobos.syntax._
import ru.tinkoff.phobos.decoding.XmlDecoder
import ru.tinkoff.phobos.derivation.semiauto._
import ru.tinkoff.phobos.encoding.XmlEncoder
import weaver.SimpleIOSuite

import scala.reflect.runtime.universe._

object PhobosTests extends SimpleIOSuite {

  object UnitEnd extends Destination[Unit] { override def name: String = "unit"; override def capability: Type = typeOf[Unit] }

  case class MyEvent(foo: Int, bar: String)

  object MyEvent {
    implicit val xmlEncoder: XmlEncoder[MyEvent] = deriveXmlEncoder("journey")
    implicit val xmlDecoder: XmlDecoder[MyEvent] = deriveXmlDecoder("journey")

    val testValue = MyEvent(10, "string")
    val testXml = "<?xml version='1.0' encoding='UTF-8'?><journey><foo>10</foo><bar>string</bar></journey>"
  }

  test("sender.asXmlSender") {
    for {
      sender <- Sender.testing[IO, Message[Unit]]
      _      <- sender.asXmlSender[MyEvent](UnitEnd).sendOne(MyEvent.testValue)
      sent   <- sender.sent
    } yield expect.all(sent == List(Message(Payload(MyEvent.testXml, Map()), UnitEnd)))
  }

  test("consumer.asXmlSender") {
    val consumer = Consumer.one[IO, Payload](Payload(MyEvent.testXml, Map())).asXmlConsumer[MyEvent]
    Consumer.toStreamBounded(1)(consumer).take(1).compile.lastOrError.map { consumedMessage =>
      expect.all(consumedMessage == MyEvent.testValue)
    }
  }

}
