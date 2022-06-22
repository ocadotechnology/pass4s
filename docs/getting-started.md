---
sidebar_position: 1
description: Pass4s documentation page
---

# Getting started

## About

Pass4s is a Scala library providing an abstract layer for app messaging. It provides implementations for platforms like AWS SQS/SNS and ActiveMQ.

## Basis installation

The library is divided into multiple modules. If you're only interested in the basic abstractions, add following to your `build.sbt` file:

```scala
// Algebraic abstractions (Sender/Consumer)
"com.ocadotechnology" %% "pass4s-kernel" % "@VERSION@"

// Message, Destination, CommittableMessage, Connector
"com.ocadotechnology" %% "pass4s-core" % "@VERSION@"

// Broker
"com.ocadotechnology" %% "pass4s-high" % "@VERSION@"
```

## Modules 

### Connectors

[ActiveMq](https://activemq.apache.org/)
```scala
// ActiveMQ connector
"com.ocadotechnology" %% "pass4s-connector-activemq" % "@VERSION@"
```

[SNS/SQS](https://aws.amazon.com/blogs/aws/queues-and-notifications-now-best-friends/)
```scala
// SNS connector
"com.ocadotechnology" %% "pass4s-connector-sns" % "@VERSION@"
// SQS connector
"com.ocadotechnology" %% "pass4s-connector-sqs" % "@VERSION@"
```

[Kinesis](https://aws.amazon.com/kinesis/)
```scala
// ActiveMQ connector
"com.ocadotechnology" %% "pass4s-connector-kinesis" % "@VERSION@"
```

### Useful utils

Extras - provides [`MessageProcessor`](modules/message-processor) for convenient way of building rich message consumers and an easy way to bind them to processor logic.
```scala
// high-level MessageProcessor
"com.ocadotechnology" %% "pass4s-extra" % "@VERSION@"
```

[S3proxy](modules/s3proxy) - seamless support for proxying large messages through s3. Useful for sorting the [large messages on sns](https://docs.aws.amazon.com/sns/latest/dg/large-message-payloads.html) kind of problems.
```scala
// s3proxy
"com.ocadotechnology" %% "pass4s-s3proxy" % "@VERSION@"

```
[Circe](https://circe.github.io/circe/) - JSON serialization/parsing support
```scala
// circe JSON senders/consumers
"com.ocadotechnology" %% "pass4s-circe" % "@VERSION@"
```

[Phobos](https://github.com/Tinkoff/phobos) - XML serialization/parsing support
```scala
// phobos XML senders/consumers
"com.ocadotechnology" %% "pass4s-phobos" % "@VERSION@"
```

Logging middleware that uses [log4cats](https://typelevel.org/log4cats/)
```scala
// logging middleware
"com.ocadotechnology" %% "pass4s-logging" % "@VERSION@"
```
