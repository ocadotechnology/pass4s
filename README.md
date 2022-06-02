# Pass4s

## About

Pass4s is a Scala library providing an abstract layer for cross app messaging. It provides implementations for platforms like AWS SQS/SNS and ActiveMQ.

## Example

See `./demo` project for usage examples.

## Dependencies

```
// Algebraic abstractions (Sender/Consumer)
"com.ocado.ospnow.wms" %% "pass4s-kernel" % version,

// Message, Destination, CommittableMessage, Connector
"com.ocado.ospnow.wms" %% "pass4s-core" % version,

// Broker
"com.ocado.ospnow.wms" %% "pass4s-high" % version,

// circe JSON senders/consumers
"com.ocado.ospnow.wms" %% "pass4s-circe" % version,

// phobos XML senders/consumers
"com.ocado.ospnow.wms" %% "pass4s-phobos" % version,

// tracing/logging middleware
"com.ocado.ospnow.wms" %% "pass4s-tracing" % version,

// warehouseId tracing syntax for Consumer
"com.ocado.ospnow.wms" %% "pass4s-tracing-warehouseid" % version,

// high-level MessageProcessor
"com.ocado.ospnow.wms" %% "pass4s-extra" % version,

// ActiveMQ connector
"com.ocado.ospnow.wms" %% "pass4s-connector-activemq" % version
```

## Changelog

**11.0.0**
- Extract logging to separate module
- Deprecate TransactionalMessageProcessor

**10.3.0**
- Introduce a new addon: use S3 proxy for large messages

**10.0.0**
- Migrate to Cats Effect 3
- Fix: `toDestination` on fifo queue gives non-fifo destination 

**8.0.0**
- Reimplement JMS module, using `akka-stream-alpakka-jms`.
- Enable sending metadata using JMS (this also improves tracing)
- Allow creating connection without `failover:` (e.g. for purpose of outbox pattern)

**7.1.0**
- Add `usingRegionAndConnectionDetails` `KinesisConnector` constructor.
  This implementation is using `StsAssumeRoleCredentialsProvider` underneath, while putting a record on stream.

**7.0.0**
- Added `SnsFifo` and `SqsFifo` traits-markers / capabilities representing sending and receiving messages in FIFO order
- Added `SnsFifoDestination`, `SqsFifoEndpoint`, `SqsFifoDestination` representing sources and destinations of messages
- Added trait `MessageGroup` used for message group extraction from messages to be sent
- Added fifo syntax for senders: `asJsonSenderWithMessageGroup`, `asXmlSenderWithMessageGroup`, `asPlaintextSenderWithMessageGroup`

**6.0.0**
- Name of SQS/SNS endpoints no longer contains full address, but it is parsed instead. This makes transaction names in NR independent from environment.

**5.1.0**
- Add `cancelableMessageProcessing` to `Source` with `true` as a default (previous behaviour)

**5.0.0**
- Add `pass4s-connector-kinesis` module. When putting a record on a stream **random UUID is used as partitionKey**,
  to use custom value see `com.ocado.ospnow.wms.pass4s.connectors.kinesis.Kinesis.partitionKeyMetadata`
  or implement custom `KinesisAttributesProvider`
- Add `asJsonSenderWithCustomMetadata` and `asXmlSenderWithCustomMetadata`
- Add support for sending messages directly to SQS

**4.0.0**
- Split `Destination` into Source and `Destination`
- Add `pass4s-connector-sns` and `pass4s-connector-sqs` modules
- Add `messageProcessingTimeout` to `Source` and handle this attribute in `Broker.fromConnector`
- Add `maxConcurrent` to `Source` and handle this attribute in `Broker.fromConnector`

**3.0.0**
- Add `parallelSessions` and `messageProcessingTimeout` setting to JmsDestination

**2.0.0**
- Broker type now have additional 1 parameter - capability. Capability is defining what connectors are supported by an
  instance of Broker. This may look like this: `Broker[IO, Jms with Sqs]`.
- Destination have parameter defining capability. e.g. `Destination[Jms]` may be used only in Brokers, which support `Jms` capability
- Capability can be `Consume`, `Produce` or both.

  If capability doesn't extend `Produce`, then compiler won't allow to initialize `Message` instance.

  If capability doesn't extend `Consume`, then compiler won't allow to initialize `Consumer` instance.

**0.2.2**
- added `TransactionalMessageProcessor.modify` that allows to customize the `Consumer`s created by it;
  thanks to this you can now add tracing and logging to your transactional message processors like this:
  ```scala
  // old, not traced
  transactionalMessageProcessor.jsonConsumer(dest)(...)
  // new, logged and traced
  transactionalMessageProcessor.modify(_.traced(dest).logged(dest)).jsonConsumer(dest)(...)
  ```

**0.2.0**
- renamed artifacts to `pass4s` and packages to `com.ocado.ospnow.wms.pact4s`
- tracing of messages (keeping `X-B3-TraceId`, creating new trace for message consumption)
- Added Payload type for wrapping a message's contents together with its headers.
  This shouldn't be noticeable if you use high-level components like `TransactionalMessageProcessor`,
  but Broker now creates Consumers with this type. You can `.map(_.text)` on one to get the raw message, like previously.
  Utilities like circe/phobos syntax were also changed to work with this type.

**0.1.0**

Breaking changes for users:

- Removed phobos from extra module
- Added Defer constraint in `asJsonConsumerWithMessage` (for the `Monad` instance used transitively)
- Renamed `MessageProcessor` to `TransactionalMessageProcessor`
- `TransactionalMessageProcessor`'s methods now return resources that run the process in the background (instead of plain effects)
- `consumeCommit` is now `consumeCommitK`, and `consumeCommit` accepts a normal function
- renamed methods in the `surround*` area of `Consumer`
- made `Sender.send` return a stream of `Unit`s instead of an empty stream

Changes in `Consumer`:

- added `map`/`imapK` as direct methods
- moved `selfProduct` to extension methods
- added `FunctorFilter` instance
- added `zip`, `parZip` and explicit monoids for them

Changes in `Sender`:

- added `send_` pipe which drains the stream (old behavior of `send`)
- added `filter`/`filterM`/`contramapFilter`/`contramapFilterM` extension methods
- added `or`, which is just `decide` as a convenience method
- added `and` extension method, which is just `product` / `.tupled`
- added `clear` on `RefSender` (`Sender.testing`)

Other improvements:

- added `logged/traced` syntax on `Sender`/`BrokerRead`/`Broker` levels, `logged` extension on `Connector`
- Consumer/Sender are now serializable
- added prioritized typeclass instance derivations (this change should be transparent to users, but it allows using combinators like `map` on `Consumer` without any constraints)
- made all extension classes consistent in keywords (`implicit final class ... (private val ...) extends AnyVal`)

**Initial snapshots (~2021-01-14: commit sha ee3c846600e1d7bea4968c04542fbe13b549526a)**

- everything

---

## Historical readme from Alpakka Tap

Library that provides support for consuming and processing JSON JMS
messages in transaction with possibility to send messages to other
queues (in JSON format or object messages) as outcome of processing and
before transaction commit.

Processing failures will result in transaction rollback.

Library is using Akka Streams and Akka Alpakka project to build
graph that consumes messages from one queue, splits processing into
configurable number of parallel workers (parallel JMS sessions),
routes produced messages to queues, commit/rollback JMS transaction.

User have to define processing block and instances of needed typeclasses/implicits
for input and output configuration.

### Usage

Add dependency:

```
"com.ocado.ospnow.wms" %% "alpakka-tap" % "6.0.0"
```

Examples of usage can be seen in
[example package](src/test/scala/com/ocado/gm/wms/tap/example).

Example shows simple stream that consumes JSON messages of type

```scala
sealed trait Input extends Product with Serializable
final case class Input1(value: Int) extends Input
final case class Input2(value: String) extends Input
```

and produces messages that can be routed to

```scala
val Dest1 = TapDestination.queue("...")
val Dest2 = TapDestination.queue("...")
val Dest3Obj = TapDestination.queue("...")
```

For `Input` user has to provide instances of

- `io.circe.Decoder[Input]`
- `SourceConfig[Input]` with the destination to consume from and the parallelism level.

for last one there is a default for any type.

For output a `TapDestination` has to be instantiated. You can use out-of-the-box factories in `TapDestination`, like
`queue` or `topic`.

A flow can be created using one of factory methods on `Tap` like

```scala
def process[F[_]: Taps: Concurrent: ProducerConfig.Ask: ConsumerConfig.Ask, A: Decoder: SourceConfig](
  pipe: Pipe[F, A, TapMessage],
  queueSize: Int = TapSender.DefaultQueueSize
): CompiledTapStream[F]
```

If processing pipe is derived from function that needs `Tap.Messages` to work (it's in shape `Tap.Messages[F] => A => F[Unit]`) then it can be converted
to `Pipe[F, A TapMessage]` by `Tap.pipeTell[F, A]`. This helper method will provide instance of `FunctorTell` backed up by `Ref` and run `processing` function with it.

For example:

```scala
val stream = Tap.process[F, Input](Tap.pipeTell[F, Input](implicit messages => yourProcessing[F])).resource //or .stream
```

There are also two other helper functions for providing pipes from existing functions:

- `liftToPipe` - converting `A => F[List[TapMessage]]` to `Pipe[F, A, TapMessage]`
- `liftToPipe_` - converting `A => F[Unit]` to `Pipe[F, A, TapMessage]`, passed function is evaluated for effect and returns empty stream.

The returned resource/stream closes the consumer/producer in `release` step.

If you want to run a transactional action for each of the messages and rollback the processed message in case of failure (e.g. if the transaction fails to be committed), you should do it the `pipe`, transacting to F.
For example, when using Slick and cats-effect, you might want to convert from DBIO to IO in that block.
If the transaction succeeds, this library commits the message and sends the messages produced by it (there's no order guarantee here though).

### Release notes

**11.0.0**

- alpakka 2.0

**10.0.0**

- circe 0.13

**7.0.1**

- Do not fail stream on ack timeout (resulted in duplicated connections) **Warning: messages, which will exceed the timeout will be rolled back, but Tap will log them as committed, due to lack information about rollback. See https://github.com/akka/alpakka/issues/2039**

**7.0.0**

- updated cats to 2.0.0
- updated fs2 to 2.1.0
- updated circe to 0.12.2

**6.8.1**

- Do not fail stream on ack timeout (resulted in duplicated connections) **Warning: messages, which will exceed the timeout will be rolled back, but Tap will log them as committed, due to lack information about rollback. See https://github.com/akka/alpakka/issues/2039**

**6.8.0**

- Fail pooled connection factory allocation if it's created without failover transport

**6.6.0**

- Default configuration of `JmsConsumerSettings` is changed to `ackTimeout = 30 seconds`, `failStreamOnAckTimeout = true`. This configuration is moved from `reference.conf` to code, as there is no guarantee how `reference.conf` of `alpakka` and `tap` are ordered, and there was such case, when `tap` config was overridden by `alpakka`

**6.5.0**

- Logging on failures in decoding or incorrect message types

**6.4.0**

- Update alpakka to 1.1.0
- By default fail stream on ack timeout

**6.3.0**

- Defaults ack timeout to 30 seconds instead of 1 second which is default in Alpakka

**6.2.0**

- Added 3rd type parameter to `Tap.stream` which replaces `TapMessage` as stream output param. Useful when message needs to be published to different broker than the one which consumer consumes from.

**6.1.0**

- New module for ActiveMQ that defines utilities for building connection factories as `cats.effect.Resource`.

**6.0.0**

- New organization for artifacts - artifact is now `"com.ocado.ospnow.wms" %% "alpakka-tap" % "6.0.0"`
- Fixed parallel consumers (the library actually uses the source config and starts multiple parallel consumer sessions now)

**5.0.0**

- upgraded Alpakka to 1.0.x
- new API for defining destinations - see TapDestination and the above examples
- new API for streaming - simple methods in `Tap` and `TapSender` are all that matters
- migrated streaming logic to fs2 - Akka Streams / Alpakka are only used at edges, then converted to functional streams
- stream processes can now be compiled as `cats.effect.Resource` or `fs2.Stream`
- you can build streams that only use JMS for reading, only producing, or do both.

**4.0.0**

- renamed to Alpakka tap
- changed versioning scheme to x.y.z
- changed packaging from `com.ocado.gm.wms.txflow` to `com.ocado.gm.wms.tap`
- added standard functions to create sinks based on functions `A => F[Chain[OutgoingMessage]]`
- added OutgoingMessagesSender which can be used for sending messages outside of Alpakka streams (e.g. REST)
