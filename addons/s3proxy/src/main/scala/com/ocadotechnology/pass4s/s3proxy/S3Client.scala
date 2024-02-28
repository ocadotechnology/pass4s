/*
 * Copyright 2024 Ocado Technology
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

import cats.effect.kernel.Async
import cats.effect.kernel.Resource
import cats.implicits._
import io.laserdisc.pure.s3.tagless.S3AsyncClientOp
import io.laserdisc.pure.s3.tagless.{Interpreter => S3Interpreter}
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider
import software.amazon.awssdk.core.async.AsyncRequestBody
import software.amazon.awssdk.core.async.AsyncResponseTransformer
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3AsyncClient
import software.amazon.awssdk.services.s3.S3AsyncClientBuilder
import software.amazon.awssdk.services.s3.model._

import java.net.URI
import scala.jdk.CollectionConverters._

trait S3Client[F[_]] {
  def getObject(bucket: String, key: String): F[Option[String]]
  def putObject(bucket: String, key: String)(payload: String): F[Unit]
  def deleteObject(bucket: String, key: String): F[Unit]
  def createBucket(bucket: String): F[Unit]
  def deleteBucket(bucket: String): F[Unit]
  def listObjects(bucket: String): F[List[String]]
}

object S3Client {

  def apply[F[_]](
    implicit ev: S3Client[F]
  ): S3Client[F] = ev

  def usingBuilder[F[_]: Async](
    s3Builder: S3AsyncClientBuilder
  ): Resource[F, S3Client[F]] =
    S3Interpreter.apply.S3AsyncClientOpResource(s3Builder).map(usingPureClient[F])

  def usingLocalAws[F[_]: Async](
    endpointOverride: URI,
    region: Region,
    credentialsProvider: AwsCredentialsProvider
  ): Resource[F, S3Client[F]] =
    usingBuilder(
      S3AsyncClient.builder().endpointOverride(endpointOverride).region(region).credentialsProvider(credentialsProvider)
    )

  def usingRegion[F[_]: Async](
    region: Region,
    endpointOverride: Option[URI] = None
  ): Resource[F, S3Client[F]] =
    usingBuilder {
      val builder = S3AsyncClient.builder().region(region)
      endpointOverride.fold(builder)(builder.endpointOverride)
    }

  def usingPureClient[F[_]: Async](client: S3AsyncClientOp[F]): S3Client[F] =
    new S3Client[F] {

      override def getObject(bucket: String, key: String): F[Option[String]] =
        client
          .getObject(
            GetObjectRequest.builder().bucket(bucket).key(key).build(),
            AsyncResponseTransformer.toBytes[GetObjectResponse]()
          )
          .map(_.asUtf8String().some)
          .recover { case _: NoSuchKeyException => none }

      override def putObject(bucket: String, key: String)(payload: String): F[Unit] =
        client
          .putObject(
            PutObjectRequest.builder().bucket(bucket).key(key).build(),
            AsyncRequestBody.fromString(payload)
          )
          .void

      override def deleteObject(bucket: String, key: String): F[Unit] =
        client
          .deleteObject(
            DeleteObjectRequest.builder().bucket(bucket).key(key).build()
          )
          .void

      override def createBucket(bucket: String): F[Unit] =
        client
          .createBucket(
            CreateBucketRequest.builder().bucket(bucket).build()
          )
          .void

      override def deleteBucket(bucket: String): F[Unit] =
        client
          .deleteBucket(
            DeleteBucketRequest.builder().bucket(bucket).build()
          )
          .void

      override def listObjects(bucket: String): F[List[String]] =
        client
          .listObjects(
            ListObjectsRequest.builder().bucket(bucket).build()
          )
          .map(_.contents().iterator().asScala.map(_.key()).toList)

    }

}
