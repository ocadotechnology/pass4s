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

object S3ProxyConfig {

  /** Sender configuration for S3 proxy
    *
    * @param bucket
    *   name of the bucket to send the large objects to
    * @param payloadSizeAttributeName
    *   optional attribute name. When provided, size of the stored message will be added with this key to message metadata
    * @param minPayloadSize
    *   optional minimal paylaod size to be met for the message to be sent to S3. No minimum means the message will always be stored in S3
    */
  final case class Sender(
    bucket: String,
    payloadSizeAttributeName: Option[String],
    minPayloadSize: Option[Long]
  )

  object Sender {
    def withSnsDefaults(bucket: String) = S3ProxyConfig.Sender(bucket, Some("ExtendedPayloadSize"), Some(262144))
  }

  /** Consumer configuration for S3 proxy
    *
    * @param payloadSizeAttributeName
    *   optional attribute name. When provided, size of the stored message will be added with this key to message metadata
    * @param shouldDeleteAfterProcessing
    *   determines if object should be deleted from s3 after successful processing on consumer
    */
  final case class Consumer(
    payloadSizeAttributeName: Option[String],
    shouldDeleteAfterProcessing: Boolean
  )

  object Consumer {
    def withSnsDefaults() = S3ProxyConfig.Consumer(Some("ExtendedPayloadSize"), shouldDeleteAfterProcessing = false)
  }

}
