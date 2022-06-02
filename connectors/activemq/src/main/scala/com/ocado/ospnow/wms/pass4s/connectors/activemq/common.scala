package com.ocado.ospnow.wms.pass4s.connectors.activemq

import akka.stream.alpakka.{jms => alpakka}

private[activemq] object common {

  def toAlpakkaDestination: (String, Jms.Type) => alpakka.Destination = {
    case (name, Jms.Type.Topic) => alpakka.Topic(name)
    case (name, Jms.Type.Queue) => alpakka.Queue(name)
  }

}
