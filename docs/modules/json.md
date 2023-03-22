---
sidebar_position: 3
description: JSON message serialization with Circe
---

# JSON

In it's core form, both the `Consumer` and `Producer` work with plain text messages. This is usually not the case for real life applications. 

This section explains how to work with messages represented in JSON. Pass4s comes with the [Circe](https://circe.github.io/circe/) support for JSON message transformation.

To use the module make sure to add following import to your `build.sbt`

```scala
// circe JSON senders/consumers
"com.ocadotechnology" %% "pass4s-circe" % "@VERSION@"
```

With that module added, you can now import additional syntax for consumers and producers. To enable the syntax add following import to your file:

```scala
import com.ocadotechnology.pass4s.circe.syntax._
```

The syntax allows you to use `.asJsonSender[T]` on the senders and `.asJsonConsumer[T]` on consumers. Please note that for this to work, you need to provide `io.circe.Encoder[T]` in case of `Sender` and `io.circe.Decoder[T]` for `Consumer`. Please refer to this section https://circe.github.io/circe/codec.html in Circe documentation for more detailed info on how encoders and decoders work.

When applying the syntax to consumer, consider using [MessageProcessor](modules/message-processor).
