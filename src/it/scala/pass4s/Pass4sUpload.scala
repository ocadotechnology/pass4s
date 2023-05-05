package pass4s

import cats.effect.IO
import cats.effect.IOApp
import com.ocadotechnology.pass4s.connectors.sqs.SqsConnector
import com.ocadotechnology.pass4s.connectors.sqs.SqsDestination
import com.ocadotechnology.pass4s.connectors.sqs.SqsUrl
import com.ocadotechnology.pass4s.core.Message
import com.ocadotechnology.pass4s.core.Message.Payload
import com.ocadotechnology.pass4s.high.Broker
import com.ocadotechnology.pass4s.s3proxy.S3Client
import com.ocadotechnology.pass4s.s3proxy.S3ProxyConfig
import com.ocadotechnology.pass4s.s3proxy.syntax._
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.slf4j.Slf4jLogger
import software.amazon.awssdk.regions.Region

import java.net.URI

object Pass4sUpload extends IOApp.Simple {
  val endpointOverride = URI.create("http://localhost.localstack.cloud:4566")
  val region = Region.US_EAST_1

  implicit val logger: Logger[IO] = Slf4jLogger.getLogger

  override def run: IO[Unit] = (for {
    sqsConnector <- SqsConnector.usingRegionWithDefaultAttributesProvider[IO](region, Some(endpointOverride))
    s3Client     <- S3Client.usingRegion[IO](region, Some(endpointOverride))
  } yield (Broker.fromConnector(sqsConnector), s3Client)).use { case (broker, implicit0(s3Client: S3Client[IO])) =>
    val body = "This message is stored in S3 as it exceeds the threshold of 32 bytes set above."
    val message = Message(Payload(body, Map.empty), SqsDestination(SqsUrl("http://localhost:4566/000000000000/queue")))
    val config = S3ProxyConfig.Sender.withSnsDefaults("bucket").copy(minPayloadSize = None)
    broker.sender.usingS3Proxy(config).sendOne(message.widen)
  }

}
