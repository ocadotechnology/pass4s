package pass4s

import com.amazon.sqs.javamessaging._
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.sqs._

import java.net.URI

object AmazonLibUpload extends App {
  val endpointOverride = URI.create("http://localhost.localstack.cloud:4566")
  val sqsClient = SqsClient.builder.endpointOverride(endpointOverride).region(Region.US_EAST_1).build
  val s3Client = S3Client.builder.endpointOverride(endpointOverride).region(Region.US_EAST_1).build

  val config = new ExtendedClientConfiguration()
    .withPayloadSupportEnabled(s3Client, "bucket", false)
    .withAlwaysThroughS3(true)
    .withLegacyReservedAttributeNameDisabled()

  val amazonSQSExtendedClient = new AmazonSQSExtendedClient(sqsClient, config)

  val message = "Amazon Extended Client"
  amazonSQSExtendedClient.sendMessage(
    model.SendMessageRequest.builder.queueUrl("http://localhost:4566/000000000000/queue").messageBody(message).build
  )
}
