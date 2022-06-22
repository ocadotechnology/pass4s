---
sidebar_position: 1
description: Message processor
---

# Message processor

Message processor provides a convenient way for building rich consumer, binding it to broker and attaching message handling logic.

The usual flow of building processor starts with initialization
```scala
val processor = MessageProcessor.init[IO]
```
Where the `IO` can be replaced with the effect of your preference. Once initialized, you can enrich the underlying consumer by subsequent `enrich` calls.
```scala
val richProcessor = 
  processor
    .enrich(_.logged)
    .enrich(_.usingS3Proxy(consumerConfig))
    .enrich(_.asJsonConsumer[String])
```
Then depending on your logic you can go either with `transact` if you use different effect or `effectful` if you want to stick to `IO` in our example. After that you bind the broker and provide the message handling logic. Keep in mind that you can resue once prepared processor like in the example below.

```scala
val processor = 
  MessageProcessor
    .init[IO]
    .enrich(_.logged)
    .enrich(_.asJsonConsumer[String])
    .transacted(runEffect)
    .bindBroker(broker)

processor.handle(Destinations.destinationA)(MyProcessor.instanceA[AppEffect])
processor.handle(Destinations.destinationB)(MyProcessor.instanceB[AppEffect])
```
