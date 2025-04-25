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

import cats.implicits.*
import io.circe.DecodingFailure.Reason.CustomReason
import io.circe.*

import scala.annotation.nowarn

/** This implementation follows
  * https://github.com/awslabs/payload-offloading-java-common-lib-for-aws/blob/7c826fccce39c589d06abffa0c7f912115212dba/src/main/java/software/amazon/payloadoffloading/PayloadS3Pointer.java#L46
  * for Java implementation interop.
  *
  * The JSON format looks like this: `["software.amazon.payloadoffloading.PayloadS3Pointer", {"s3BucketName": "bucket", "s3Key": "key"}]`
  *
  * Due to the bug, in the older versions of pass4s following format was used: `{"s3BucketName": "bucket", "s3Key": "key"}` For compability
  * see: [[com.ocadotechnology.pass4s.s3proxy.PayloadS3Pointer.legacyFormatDecoder]] and
  * [[com.ocadotechnology.pass4s.s3proxy.PayloadS3Pointer.legacyFormatEncoder]]
  */
final case class PayloadS3Pointer(s3BucketName: String, s3Key: String)

object PayloadS3Pointer {
  private val amazonPackageName = "software.amazon.payloadoffloading.PayloadS3Pointer"
  private val amazonPackageNameJson = Json.fromString(amazonPackageName)

  @deprecated("use encoder that is compatible with Amazon Extended Client Library", "0.4.0")
  val legacyFormatEncoder: Encoder[PayloadS3Pointer] = (a: PayloadS3Pointer) =>
    Json.obj(
      "s3BucketName" -> Json.fromString(a.s3BucketName),
      "s3Key" -> Json.fromString(a.s3Key)
    )

  @nowarn("cat=deprecation")
  val encoder: Encoder[PayloadS3Pointer] = (a: PayloadS3Pointer) =>
    Json.arr(
      amazonPackageNameJson,
      legacyFormatEncoder(a)
    )

  @deprecated("use decoder that is compatible with Amazon Extended Client Library", "0.4.0")
  val legacyFormatDecoder: Decoder[PayloadS3Pointer] = (c: HCursor) =>
    for {
      bucket <- c.downField("s3BucketName").as[String]
      key    <- c.downField("s3Key").as[String]
    } yield PayloadS3Pointer(bucket, key)

  @nowarn("cat=deprecation")
  val decoder: Decoder[PayloadS3Pointer] = {
    val arrayDecoder: Decoder[PayloadS3Pointer] = (c: HCursor) => {
      val aCursor = c.downArray
      for {
        packageName <- aCursor.as[String]
        _           <- (packageName === amazonPackageName).guard[Option].toRight {
                         val errorMessage = s"Expecting [$amazonPackageName] as a first element of the array. Received: [$packageName]."
                         DecodingFailure(CustomReason(errorMessage), aCursor)
                       }
        pointer     <- aCursor.right.as(legacyFormatDecoder)
      } yield pointer
    }
    legacyFormatDecoder or arrayDecoder
  }

  implicit val codec: Codec[PayloadS3Pointer] = Codec.from(decoder, encoder)
}
