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

package com.ocadotechnology.pass4s.connectors.sns

import weaver.SimpleIOSuite

object SnsConnectorTest extends SimpleIOSuite {
  val arn = "arn:aws:sns:euw1:52452456:some-name"
  val invalidArn = "some-name"

  def nameOfSnsDestination(
    arn: String
  ) = SnsDestination(SnsArn(arn)).name

  pureTest("SNS ARN") {
    expect(nameOfSnsDestination(arn) == "some-name")
  }

  pureTest("Invalid ARN") {
    expect(nameOfSnsDestination(invalidArn) == invalidArn)
  }
}
