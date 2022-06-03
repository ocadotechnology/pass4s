package com.ocadotechnology.pass4s.connectors.activemq

import cats.effect.Resource
import cats.effect.Sync
import cats.implicits._
import org.apache.activemq.ActiveMQConnectionFactory
import org.apache.activemq.pool.PooledConnectionFactory

object ConnectionFactories {

  /**
    * Creates a pooled ActiveMQ connection factory.
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

  /**
    * Creates an ActiveMQ connection factory.
    */
  def unpooled[F[_]: Sync](username: String, password: String, url: String): F[ActiveMQConnectionFactory] =
    Sync[F].delay(new ActiveMQConnectionFactory(username, password, url))

  /**
    * Wraps the base factory in a connection pool.
    */
  def makePooled[F[_]: Sync](baseFactory: ActiveMQConnectionFactory): Resource[F, PooledConnectionFactory] =
    Resource.make(Sync[F].delay(new PooledConnectionFactory(baseFactory)))(pcf => Sync[F].delay(pcf.stop()))

}
