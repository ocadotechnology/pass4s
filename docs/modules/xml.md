---
sidebar_position: 4
description: XML message serialization with Phobos
---

# XML

This section explains how to work with messages represented in XML. Pass4s comes with the [Phobos](https://github.com/Tinkoff/phobos/) support for XML message transformation.

To use the module make sure to add following import to your `build.sbt`

```scala
// phobos XML senders/consumers
"com.ocadotechnology" %% "pass4s-phobos" % "@VERSION@"
```

With that module added, you can now import additional syntax for consumers and producers. To enable the syntax add following import to your file:

```scala
import com.ocadotechnology.pass4s.phobos.syntax._
```

The syntax allows you to use `.asXmlSender[T]` on the senders and `.asXmlConsumer[T]` on consumers. Please note that for this to work, you need to provide `XmlEncoder[T]` in case of `Sender` and `XmlDecoder[T]` for `Consumer`. 

Here's how to create most basic encoder and decoder using Phobos:

```scala
import ru.tinkoff.phobos.decoding._
import ru.tinkoff.phobos.encoding._
import ru.tinkoff.phobos.syntax._
import ru.tinkoff.phobos.derivation.semiauto._

final case class XmlMessage(description: String, value: Long, rows: List[String])

object XmlMessage {
  implicit val xmlEncoder: XmlEncoder[XmlMessage] = deriveXmlEncoder("xmlMessage")
  implicit val xmlDecoder: XmlDecoder[XmlMessage] = deriveXmlDecoder("xmlMessage")
}
```

Please refer to the project repository https://github.com/Tinkoff/phobos for more detailed guide on using Phobos.


When applying the syntax to consumer, consider using [MessageProcessor](message-processor).
