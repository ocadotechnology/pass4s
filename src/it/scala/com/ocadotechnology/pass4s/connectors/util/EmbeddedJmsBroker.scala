package com.ocadotechnology.pass4s.connectors.util

import akka.actor.ActorSystem

import cats.effect.IO
import cats.effect.Resource
import com.ocadotechnology.pass4s.connectors.activemq.JmsConnector
import com.ocadotechnology.pass4s.connectors.activemq.JmsConnector.JmsConnector
import org.typelevel.log4cats.Logger
import org.apache.activemq.ActiveMQConnectionFactory
import org.apache.activemq.broker.BrokerService
import org.apache.activemq.broker.region.policy.IndividualDeadLetterStrategy
import org.apache.activemq.broker.region.policy.PolicyEntry
import org.apache.activemq.broker.region.policy.PolicyMap

import scala.util.Random

object EmbeddedJmsBroker {

  def actorSystemResource: Resource[IO, ActorSystem] =
    Resource.make[IO, ActorSystem](IO(ActorSystem.create("TestActorSystem"))) { actorSystem =>
      IO.fromFuture(IO(actorSystem.terminate())).void
    }

  def brokerServiceResource(brokerName: String, address: Option[String] = None): Resource[IO, BrokerService] =
    Resource.make {
      IO {
        val brokerService = new BrokerService()
        brokerService.setPersistent(false)
        brokerService.setUseJmx(false)
        brokerService.setDestinationPolicy(new PolicyMap())
        brokerService.getDestinationPolicy.setDefaultEntry {
          val policyEntry = new PolicyEntry()
          policyEntry.setDeadLetterStrategy(new IndividualDeadLetterStrategy())
          policyEntry
        }
        brokerService.setBrokerName(brokerName)
        address.foreach(brokerService.addConnector)
        brokerService.start()
        require(brokerService.waitUntilStarted(), "Embedded broker didn't get up")
        brokerService
      }
    }(brokerService => IO { brokerService.stop(); brokerService.waitUntilStopped() })

  def createJmsConnector(
    brokerUrl: String
  )(
    implicit logger: Logger[IO],
    actorSystem: ActorSystem
  ): Resource[IO, JmsConnector[IO]] = {
    val embeddedAMQConnectionFactory = new ActiveMQConnectionFactory(brokerUrl)
    embeddedAMQConnectionFactory.getRedeliveryPolicy.setMaximumRedeliveries(2)
    JmsConnector.singleBroker[IO](embeddedAMQConnectionFactory)
  }

  def createBrokerAndConnectToIt(implicit logger: Logger[IO]): Resource[IO, JmsConnector[IO]] =
    for {
      implicit0(as: ActorSystem) <- actorSystemResource
      brokerName                 <- Resource.eval(IO(Random.alphanumeric.take(8).mkString)).map(randomSuffix => s"broker-$randomSuffix")
      _                          <- brokerServiceResource(brokerName)
      connector                  <- createJmsConnector(s"vm://$brokerName")
    } yield connector

}
