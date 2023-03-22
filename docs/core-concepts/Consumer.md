---
sidebar_position: 2
---

# Consumer

Consumer is an abstraction for continuous process of executing logic upon receiving a message.

It's defined as a function in following shape: `(A => F[Unit]) => F[Unit]`. This means a function that:
- takes an argument of type `A => F[Unit]` - think of it as the processing logic type
- returns `F[Unit]` means that consuming itself is only a side effect and yields no real value

```scala
trait Consumer[F[_], +A] extends ((A => F[Unit]) => F[Unit]) with Serializable { self =>
  /** Starts the consumer, passing every message through the processing function `f`. Think of it like of an `evalMap` on [[Stream]] or
    * `use` on [[cats.effect.Resource]].
    */
  def consume(f: A => F[Unit]): F[Unit]

  /** Starts the consumer, but allows the processing function `f` to be in a different effect than that of the consumer's. A `commit`
    * function also needs to be passed - it will be used after every message.
    */
  def consumeCommit[T[_]](commit: T[Unit] => F[Unit])(f: A => T[Unit]): F[Unit] = self.consume(f andThen commit)
}
```

To start a consumer, you need a function that will handle messages of type `A` and return effects in `F[_]`. As you can see in the example above, the consumer can also be transactional, meaning it can perform an action in one effect and then translate the result to the other. It's especially useful when you want to perform database operations in `ConnectionIO[_]` while your application effect is `IO[_]`.

The end user usually doesn't instantiate the `Consumer` directly. Instead they would usually get one from [`Broker`](broker) or [`MessageProcessor`](../modules/message-processor).


This abstraction comes with a lot of useful manipulators as well as `Semigroup`, `Monoid`, `Functor` and `Monad` instances. Please refer to [`Consumer.scala` sources](https://github.com/ocadotechnology/pass4s/blob/main/kernel/src/main/scala/com/ocadotechnology/pass4s/kernel/Consumer.scala) and the scaladocs.

# Basic usage

Here's a simple consumer implementation configured to use with our [localstack setup](localstack). If you want to run it locally, simply save the file somewhere and run it using [scala-cli](https://scala-cli.virtuslab.org/install) using `scala-cli run filename.scala`

```scala
//> using scala "2.13"
//> using lib "com.ocadotechnology::pass4s-kernel:0.2.2"
//> using lib "com.ocadotechnology::pass4s-core:0.2.2"
//> using lib "com.ocadotechnology::pass4s-high:0.2.2"
//> using lib "com.ocadotechnology::pass4s-connector-sqs:0.2.2"
//> using lib "org.typelevel::log4cats-noop:2.5.0"

import cats.effect.ExitCode
import cats.effect.IO
import cats.effect.IOApp
import cats.implicits._
import com.ocadotechnology.pass4s.connectors.sqs.SqsConnector
import com.ocadotechnology.pass4s.connectors.sqs.SqsEndpoint
import com.ocadotechnology.pass4s.connectors.sqs.SqsSource
import com.ocadotechnology.pass4s.connectors.sqs.SqsUrl
import com.ocadotechnology.pass4s.core.Source
import com.ocadotechnology.pass4s.high.Broker
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.noop.NoOpLogger
import software.amazon.awssdk.auth.credentials.AnonymousCredentialsProvider
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.regions.Region

import java.net.URI

object BaseConsumer extends IOApp {

  override def run(args: List[String]): IO[ExitCode] = {
    implicit val ioLogger: Logger[IO] = NoOpLogger[IO]

    // Initialize credentials
    val awsCredentials = AwsBasicCredentials.create("test", "AWSSECRET")
    val localstackURI = new URI("http://localhost:4566")
    val sqsSource = SqsEndpoint(SqsUrl("http://localhost:4566/000000000000/local_queue"))

    val credentialsProvider = StaticCredentialsProvider.create(awsCredentials)
    // Create connector resource using provided credentials 
    val sqsConnector =
      SqsConnector.usingLocalAwsWithDefaultAttributesProvider[IO](localstackURI, Region.EU_WEST_2, credentialsProvider)

    sqsConnector.use { connector => // obtain the connector resource
      val broker = Broker.fromConnector(connector) // create broker from connector

      IO.println(s"Processor listening for messages on $sqsSource") *>
        broker
          .consumer(sqsSource) // obtain the consumer for certain SQS source
          .consume(message => IO.println(s"Received message: $message")) // bind consumer logic
          .background // run in background
          .void
          .use(_ => IO.never)
    }
  }
}
```

This is a rather raw way of using consumer, you might want to use [MessageProcessor](modules/message-processor) for more elasticity and enriched syntax.
