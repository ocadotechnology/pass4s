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

import io.circe._

/** This implementation follows
  * https://github.com/awslabs/payload-offloading-java-common-lib-for-aws/blob/7c826fccce39c589d06abffa0c7f912115212dba/src/main/java/software/amazon/payloadoffloading/PayloadS3Pointer.java#L46
  * for Java implementation interop
  */
final case class PayloadS3Pointer(
  s3BucketName: String,
  s3Key: String
)

object PayloadS3Pointer {

  val encoder: Encoder[PayloadS3Pointer] = new Encoder[PayloadS3Pointer] {

    override final def apply(
      a: PayloadS3Pointer
    ): Json =
      Json.obj(
        "s3BucketName" -> Json.fromString(a.s3BucketName),
        "s3Key" -> Json.fromString(a.s3Key)
      )

  }

  val decoder: Decoder[PayloadS3Pointer] = new Decoder[PayloadS3Pointer] {

    override final def apply(
      c: HCursor
    ): Decoder.Result[PayloadS3Pointer] =
      for {
        bucket <- c.downField("s3BucketName").as[String]
        key    <- c.downField("s3Key").as[String]
      } yield PayloadS3Pointer(bucket, key)

  }

  implicit val codec: Codec[PayloadS3Pointer] = Codec.from(decoder, encoder)
}
