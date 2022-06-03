# Pass4s

## About

Pass4s is a Scala library providing an abstract layer for cross app messaging. It provides implementations for platforms like AWS SQS/SNS and ActiveMQ.

## Example

See `./demo` project for usage examples.

## Dependencies

```
// Algebraic abstractions (Sender/Consumer)
"com.ocadotechnology" %% "pass4s-kernel" % version,

// Message, Destination, CommittableMessage, Connector
"com.ocadotechnology" %% "pass4s-core" % version,

// Broker
"com.ocadotechnology" %% "pass4s-high" % version,

// circe JSON senders/consumers
"com.ocadotechnology" %% "pass4s-circe" % version,

// phobos XML senders/consumers
"com.ocadotechnology" %% "pass4s-phobos" % version,

// logging middleware
"com.ocadotechnology" %% "pass4s-logging" % version,

// warehouseId tracing syntax for Consumer
"com.ocadotechnology" %% "pass4s-tracing-warehouseid" % version,

// high-level MessageProcessor
"com.ocadotechnology" %% "pass4s-extra" % version,

// ActiveMQ connector
"com.ocadotechnology" %% "pass4s-connector-activemq" % version
```