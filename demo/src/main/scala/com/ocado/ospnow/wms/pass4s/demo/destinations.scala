package com.ocadotechnology.pass4s.demo

import com.ocadotechnology.pass4s.connectors.activemq.JmsDestination
import com.ocadotechnology.pass4s.connectors.activemq.JmsSource

object Destinations {
  val inventoryEvents = JmsSource.queue("Inventory.Events")
  val orderUpdates = JmsDestination.topic("VirtualTopic.Order.Updates")
  val orderEvents = JmsDestination.topic("VirtualTopic.Order.Events")
}
