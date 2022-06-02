package com.ocado.ospnow.wms.pass4s.circe

import com.ocado.ospnow.wms.pass4s.core.Destination
import com.ocado.ospnow.wms.pass4s.core.Message
import io.circe.Encoder
import io.circe.syntax._

object JsonMessage {
  def apply[A: Encoder, P](body: A, destination: Destination[P], metadata: Map[String, String] = Map()): Message[P] =
    Message(Message.Payload(body.asJson.noSpaces, metadata), destination)
}
