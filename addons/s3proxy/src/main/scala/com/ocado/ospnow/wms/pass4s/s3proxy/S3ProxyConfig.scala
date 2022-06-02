package com.ocado.ospnow.wms.pass4s.s3proxy

object S3ProxyConfig {

  /**
    * Sender configuration for S3 proxy
    *
    * @param bucket name of the bucket to send the large objects to
    * @param payloadSizeAttributeName optional attribute name. When provided, size of the stored message will be added with this key to message metadata
    * @param minPayloadSize optional minimal paylaod size to be met for the message to be sent to S3. No minimum means the message will always be stored in S3
    */
  final case class Sender(
    bucket: String,
    payloadSizeAttributeName: Option[String],
    minPayloadSize: Option[Long]
  )

  object Sender {
    def withSnsDefaults(bucket: String) = S3ProxyConfig.Sender(bucket, Some("ExtendedPayloadSize"), Some(262144))
  }

  /**
    * Consumer configuration for S3 proxy
    *
    * @param payloadSizeAttributeName optional attribute name. When provided, size of the stored message will be added with this key to message metadata
    * @param shouldDeleteAfterProcessing determines if object should be deleted from s3 after successful processing on consumer
    */
  final case class Consumer(
    payloadSizeAttributeName: Option[String],
    shouldDeleteAfterProcessing: Boolean
  )

  object Consumer {
    def withSnsDefaults() = S3ProxyConfig.Consumer(Some("ExtendedPayloadSize"), shouldDeleteAfterProcessing = false)
  }

}
