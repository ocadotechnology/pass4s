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

package com.ocadotechnology.pass4s.core

object groupId {

  @scala.annotation.implicitNotFound(
    "Technology (${P}) must be FIFO-aware to use with FIFO syntax.\n" +
      "You should look for a FIFO-aware version of the technology you're using: e.g. if you're using `Sns`, use `SnsFifo` instead.\n" +
      "The technology is determined by the destination type."
  )
  trait GroupIdMeta[P] {
    def groupIdKey: String
  }

  object GroupIdMeta {
    trait Absent[P]

    object Absent {

      @scala.annotation.implicitAmbiguous(
        "Destination must not require group ids.\n" +
          "Use FIFO syntax for destinations requiring message grouping (here: ${P})"
      )
      implicit def default[P]: Absent[P] = new Absent[P] {}

      @scala.annotation.nowarn("cat=unused") // this is supposed to be unused
      implicit def ambiguous[P: GroupIdMeta]: Absent[P] = new Absent[P] {}
      def iKnowWhatImDoing[P]: Absent[P] = new Absent[P] {}
    }

  }

  trait MessageGroup[A] {
    def groupId(a: A): String
  }

}
