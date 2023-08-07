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

package com.ocadotechnology.pass4s.connectors.pekko.activemq

import cats.effect.Resource
import cats.effect.Sync
import cats.implicits._
import org.apache.activemq.ActiveMQConnectionFactory
import org.apache.activemq.pool.PooledConnectionFactory

object ConnectionFactories {

  /** Creates a pooled ActiveMQ connection factory.
    *
    * Use `failover:(tcp://address)` to be sure that send operation is never failing. (Connection retries are handled by connection factory)
    * Read documentation: https://activemq.apache.org/failover-transport-reference.html
    *
    * Use raw `tcp://address` to make send operation able to fail. This may be useful when working with outbox pattern
    */
  def pooled[F[_]: Sync](username: String, password: String, url: String): Resource[F, PooledConnectionFactory] =
    Resource.suspend {
      unpooled[F](username, password, url).map(makePooled[F](_))
    }

  /** Creates an ActiveMQ connection factory.
    */
  def unpooled[F[_]: Sync](username: String, password: String, url: String): F[ActiveMQConnectionFactory] =
    Sync[F].delay(new ActiveMQConnectionFactory(username, password, url))

  /** Wraps the base factory in a connection pool.
    */
  def makePooled[F[_]: Sync](baseFactory: ActiveMQConnectionFactory): Resource[F, PooledConnectionFactory] =
    Resource.make(Sync[F].delay(new PooledConnectionFactory(baseFactory)))(pcf => Sync[F].delay(pcf.stop()))

}
