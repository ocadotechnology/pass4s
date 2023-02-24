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

package com.ocadotechnology.pass4s.circe

import com.ocadotechnology.pass4s.core.Destination
import com.ocadotechnology.pass4s.core.Message
import io.circe.Encoder
import io.circe.syntax._

object JsonMessage {

  def apply[A: Encoder, P](
    body: A,
    destination: Destination[P],
    metadata: Map[String, String] = Map()
  ): Message[P] =
    Message(Message.Payload(body.asJson.noSpaces, metadata), destination)

}
