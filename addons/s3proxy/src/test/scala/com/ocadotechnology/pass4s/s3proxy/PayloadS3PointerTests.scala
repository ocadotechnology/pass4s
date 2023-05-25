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

package com.ocadotechnology.pass4s.s3proxy

import cats.implicits._
import io.circe.DecodingFailure.Reason.CustomReason
import io.circe.Json
import io.circe.literal._
import io.circe.syntax.EncoderOps
import weaver.FunSuite

import scala.annotation.nowarn

@nowarn("cat=deprecation")
object PayloadS3PointerTests extends FunSuite {

  private val legacyFormat = json"""{"s3BucketName": "bucket", "s3Key": "key"}"""

  private val correctFormat = json"""[
    "software.amazon.payloadoffloading.PayloadS3Pointer",
    {"s3BucketName": "bucket", "s3Key": "key"}
  ]"""

  private val value = PayloadS3Pointer(s3BucketName = "bucket", s3Key = "key")

  // decoding

  test("decode from correct format") {
    expect(correctFormat.as[PayloadS3Pointer] == Right(value))
  }

  test("decode from legacy format") {
    expect(legacyFormat.as[PayloadS3Pointer] == Right(value))
  }

  test("potential error from the decoder should direct an user to the correct format (mention about missing array)") {
    val decodingResult = Json.fromString("foo").as[PayloadS3Pointer]
    val expectedReason = CustomReason("Couldn't decode [0]")
    expect(decodingResult.leftMap(_.reason) == Left(expectedReason))
  }

  test("decoding should fail if the package name of a pointer is incorrect") {
    val decodingResult = json"""["foo", {"s3BucketName": "bucket", "s3Key": "key"}]""".as[PayloadS3Pointer]
    val expectedReason =
      CustomReason("Expecting [software.amazon.payloadoffloading.PayloadS3Pointer] as a first element of the array. Received: [foo].")
    expect(decodingResult.leftMap(_.reason) == Left(expectedReason))
  }

  // encoding

  test("encode to correct format") {
    expect(value.asJson == correctFormat)
  }

  test("encode to legacy format if intended") {
    expect(value.asJson(PayloadS3Pointer.legacyFormatEncoder) == legacyFormat)
  }
}
