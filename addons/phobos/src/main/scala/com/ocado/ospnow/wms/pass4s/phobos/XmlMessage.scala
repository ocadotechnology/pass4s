package com.ocadotechnology.pass4s.phobos

import com.ocadotechnology.pass4s.core.Destination
import com.ocadotechnology.pass4s.core.Message
import ru.tinkoff.phobos.encoding.XmlEncoder

object XmlMessage {

  def apply[A: XmlEncoder, P](
    body: A,
    destination: Destination[P],
    metadata: Map[String, String] = Map(),
    charset: String = "UTF-8"
  ): Message[P] =
    Message(Message.Payload(XmlEncoder[A].encode(body, charset), metadata), destination)

}
