---
sidebar_position: 1
---

# Sender

Sender is a basic abstraction over the possibility to send single message. Its simplified definition looks like this:

```scala
trait Sender[F[_], -A] extends (A => F[Unit]) with Serializable {

  /** Sends a single message.
    */
  def sendOne(msg: A): F[Unit]

  /** Alias for [[sendOne]]. Thanks to this, you can pass a Sender where a function type is expected.
    */
  def apply(msg: A): F[Unit] = sendOne(msg)

}
```

As you can see `Sender` is basically a type of function of `A => F[Unit]` shape. It comes with many combinators for mapping, filtering and combining `Sender`s, as well as `Functor` and `Monoid` instances.

Please refer to [`Sender.scala` sources](https://github.com/ocadotechnology/pass4s/blob/main/kernel/src/main/scala/com/ocadotechnology/pass4s/kernel/Sender.scala) and the scaladocs.

The typical way of obtaining a `Sender` instance is by instantiating the [Broker](Broker) first.

# Basic example

Here's a simple sender implementation configured to use with our [localstack setup](../localstack). If you want to run it locally, simply save the file somewhere and run it using [scala-cli](https://scala-cli.virtuslab.org/install) using `scala-cli run filename.scala`

```scala
//> using scala "2.13"
//> using lib "com.ocadotechnology::pass4s-kernel:0.3.1"
//> using lib "com.ocadotechnology::pass4s-core:0.3.1"
//> using lib "com.ocadotechnology::pass4s-high:0.3.1"
//> using lib "com.ocadotechnology::pass4s-connector-sns:0.3.1"
//> using lib "org.typelevel::log4cats-noop:2.5.0"

import cats.effect.ExitCode
import cats.effect.IO
import cats.effect.IOApp
import cats.implicits._
import com.ocadotechnology.pass4s.connectors.sns.SnsArn
import com.ocadotechnology.pass4s.connectors.sns.SnsConnector
import com.ocadotechnology.pass4s.connectors.sns.SnsDestination
import com.ocadotechnology.pass4s.core.Message
import com.ocadotechnology.pass4s.core.Source
import com.ocadotechnology.pass4s.high.Broker
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.LoggerFactory
import org.typelevel.log4cats.noop.NoOpLogger
import software.amazon.awssdk.auth.credentials.AnonymousCredentialsProvider
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.regions.Region

import java.net.URI

object Producer extends IOApp {

  override def run(args: List[String]): IO[ExitCode] = {
    implicit val ioLogger: Logger[IO] = NoOpLogger[IO]

    // Initialize credentials
    val awsCredentials = AwsBasicCredentials.create("test", "AWSSECRET");
    val snsDestination = SnsDestination(SnsArn("arn:aws:sns:eu-west-2:000000000000:local_sns"))
    val localstackURI = new URI("http://localhost:4566")

    val credentialsProvider = StaticCredentialsProvider.create(awsCredentials)

    // Create connector resource using provided credentials 
    val snsConnector =
      SnsConnector.usingLocalAwsWithDefaultAttributesProvider[IO](localstackURI, Region.EU_WEST_2, credentialsProvider)

    snsConnector.use { connector => // obtain the connector resource
      val broker = Broker.fromConnector(connector)

      val message = Message(Message.Payload("hello world!", Map()), snsDestination)

      IO.println(s"Sending message $message to $snsDestination") *>
        broker.sender.sendOne(message) *> // use the sender to send one message
        IO.println("Sent, exiting!").as(ExitCode.Success)
    }
  }
}
```
