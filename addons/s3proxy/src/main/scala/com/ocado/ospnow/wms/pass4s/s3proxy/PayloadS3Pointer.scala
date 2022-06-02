package com.ocado.ospnow.wms.pass4s.s3proxy

import io.circe._

/**
  * This implementation follows https://github.com/awslabs/payload-offloading-java-common-lib-for-aws/blob/7c826fccce39c589d06abffa0c7f912115212dba/src/main/java/software/amazon/payloadoffloading/PayloadS3Pointer.java#L46
  * for Java implementation interop
  */
final case class PayloadS3Pointer(s3BucketName: String, s3Key: String)

object PayloadS3Pointer {

  val encoder: Encoder[PayloadS3Pointer] = new Encoder[PayloadS3Pointer] {

    override final def apply(a: PayloadS3Pointer): Json =
      Json.obj(
        ("s3BucketName" -> Json.fromString(a.s3BucketName)),
        ("s3Key" -> Json.fromString(a.s3Key))
      )

  }

  val decoder: Decoder[PayloadS3Pointer] = new Decoder[PayloadS3Pointer] {

    override final def apply(c: HCursor): Decoder.Result[PayloadS3Pointer] =
      for {
        bucket <- c.downField("s3BucketName").as[String]
        key    <- c.downField("s3Key").as[String]
      } yield PayloadS3Pointer(bucket, key)

  }

  implicit val codec: Codec[PayloadS3Pointer] = Codec.from(decoder, encoder)
}