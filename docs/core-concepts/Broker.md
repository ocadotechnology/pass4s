---
sidebar_position: 3
---

# Broker

Broker is a higher level abstraction than `Consumer` and `Sender`, it resides in `"com.ocadotechnology" %% "pass4s-high" % "@VERSION@"` module.

It aims to:
 - Provide an easy way to build sender and consumer out of a connector
 - Allow the user to route the requests - select the right sender/consumer logic based on the source of the message

```scala
trait Broker[F[_], +P] {
  def consumer[R >: P](source: Source[R]): Consumer[F, Payload]

  def sender[R >: P]: Sender[F, Message[R]]
}

object Broker {
  def fromConnector[F[_]: Async, P](connector: Connector[F, P]): Broker[F, P]
  def routed[F[_], P](chooseBroker: End[P] => Broker[F, P]): Broker[F, P]
}
```

Sample broker initialization might look like this:

```scala
val brokerResource = Akka
  .system[IO]
  .flatMap { implicit sys =>
    implicit val connectorLogger: Logger[IO] = Slf4jLogger.getLoggerFromClass[IO](classOf[Connector[IO, Jms]])

    JmsConnector
      .singleBroker[IO](
        "admin",
        "admin",
        "failover:(tcp://localhost:61616)"
      )
      .map(_.logged)
  }
  .map(Broker.fromConnector[IO, Jms])
```

Plese see the [`DemoMain.scala` for full usage example](https://github.com/ocadotechnology/pass4s/blob/main/demo/src/main/scala/com/ocadotechnology/pass4s/demo/DemoMain.scala).
