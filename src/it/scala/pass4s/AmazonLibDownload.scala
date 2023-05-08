package pass4s

import com.amazon.sqs.javamessaging._
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.sqs._

import java.net.URI

object AmazonLibDownload extends App {
  val endpointOverride = URI.create("http://localhost.localstack.cloud:4566")
  val sqsClient = SqsClient.builder.endpointOverride(endpointOverride).region(Region.US_EAST_1).build
  val s3Client = S3Client.builder.endpointOverride(endpointOverride).region(Region.US_EAST_1).build

  val config = new ExtendedClientConfiguration()
    .withPayloadSupportEnabled(s3Client, "bucket", false)
    .withAlwaysThroughS3(true)

  val amazonSQSExtendedClient = new AmazonSQSExtendedClient(sqsClient, config)

  val receiveMessageResponse = amazonSQSExtendedClient.receiveMessage(
    model.ReceiveMessageRequest.builder.queueUrl("http://localhost:4566/000000000000/queue").visibilityTimeout(2).build
  )

  System.out.println("Received message is " + receiveMessageResponse.messages.get(0).body)
}
