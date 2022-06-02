package com.ocado.ospnow.wms.pass4s.connectors.snssqs

import cats.implicits._
import cats.effect.IO
import cats.effect.Resource
import com.ocado.ospnow.wms.pass4s.connectors.util.MockServerContainerUtils._
import com.ocado.ospnow.wms.pass4s.connectors.sqs.Sqs
import com.ocado.ospnow.wms.pass4s.connectors.sqs.SqsClientException
import com.ocado.ospnow.wms.pass4s.connectors.sqs.SqsConnector
import com.ocado.ospnow.wms.pass4s.connectors.sqs.SqsEndpoint
import com.ocado.ospnow.wms.pass4s.connectors.sqs.SqsUrl
import com.ocado.ospnow.wms.pass4s.high.Broker
import com.ocado.ospnow.wms.pass4s.kernel.Consumer
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.slf4j.Slf4jLogger
import org.apache.commons.codec.digest.DigestUtils
import org.mockserver.client.MockServerClient
import org.mockserver.matchers.Times
import org.mockserver.model.HttpRequest.request
import org.mockserver.model.HttpRequest
import org.mockserver.model.HttpResponse
import org.mockserver.model.HttpResponse.response
import org.mockserver.model.Parameter.param
import org.mockserver.model.ParameterBody.params
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.regions.Region
import weaver.MutableIOSuite

import java.net.URI

object SqsRecoveryTests extends MutableIOSuite {
  override type Res = (Broker[IO, Sqs], MockServerClient)

  implicit val logger: Logger[IO] = Slf4jLogger.getLogger

  override def sharedResource: Resource[IO, (Broker[IO, Sqs], MockServerClient)] =
    for {
      container <- containerResource()
      connector <- SqsConnector.usingLocalAwsWithDefaultAttributesProvider(
                     URI.create(container.endpoint),
                     Region.EU_WEST_1,
                     StaticCredentialsProvider.create(AwsBasicCredentials.create("AccessKey", "SecretKey"))
                   )
    } yield (Broker.fromConnector(connector), createClient(container))

  test("should recover after receiving temporary errors from AWS").usingRes {
    case (broker, mockServerClient) =>
      val source = SqsEndpoint(SqsUrl("http://sqs/some-queue1"), SqsEndpoint.Settings(waitTimeSeconds = 1))
      val requestMatcher = sqsReceiveMessageRequest(source.url.value)
      for {
        _        <- IO(mockServerClient.when(requestMatcher, Times.exactly(1)).respond(sqsReceiveMessageResponseSuccess("foo")))
        // AWS client is retrying request once by itself, so we need more than 1 failure from AWS side
        _        <- IO(mockServerClient.when(requestMatcher, Times.exactly(2)).respond(sqsReceiveMessageResponseFailure))
        _        <- IO(mockServerClient.when(requestMatcher, Times.exactly(3)).respond(sqsReceiveMessageResponseFailure))
        _        <- IO(mockServerClient.when(requestMatcher, Times.exactly(4)).respond(sqsReceiveMessageResponseFailure))
        _        <- IO(mockServerClient.when(requestMatcher, Times.exactly(5)).respond(sqsReceiveMessageResponseSuccess("bar")))
        messages <- Consumer.toStreamBounded(maxSize = 1)(broker.consumer(source)).take(2).compile.toList
      } yield expect(messages.map(_.text) == List("foo", "bar"))
  }

  test("should fail consuming and rethrow exception if the error occurs at the beginning of the consume").usingRes {
    case (broker, mockServerClient) =>
      val source = SqsEndpoint(SqsUrl("http://sqs/some-queue2"), SqsEndpoint.Settings(waitTimeSeconds = 1))
      val requestMatcher = sqsReceiveMessageRequest(source.url.value)
      for {
        // AWS client is retrying request once by itself, so we need more than 1 failure from AWS side
        _      <- IO(mockServerClient.when(requestMatcher, Times.exactly(1)).respond(sqsReceiveMessageResponseFailure))
        _      <- IO(mockServerClient.when(requestMatcher, Times.exactly(2)).respond(sqsReceiveMessageResponseFailure))
        _      <- IO(mockServerClient.when(requestMatcher, Times.exactly(3)).respond(sqsReceiveMessageResponseFailure))
        _      <- IO(mockServerClient.when(requestMatcher, Times.exactly(4)).respond(sqsReceiveMessageResponseSuccess("bar")))
        result <- broker.consumer(source).consume(_ => IO.unit).attempt
      } yield expect(result.leftMap(_.getClass) == Left(classOf[SqsClientException]))
  }

  def sqsReceiveMessageRequest(queueUrl: String): HttpRequest =
    request("/")
      .withMethod("POST")
      .withHeader("Content-Type", "application/x-www-form-urlencoded; charset=utf-8")
      .withBody(params(param("Action", "ReceiveMessage"), param("QueueUrl", queueUrl)))

  def sqsReceiveMessageResponseSuccess(body: String): HttpResponse = {
    val md5OfBody = DigestUtils.md5Hex(body)
    response(s"""
      <ReceiveMessageResponse>
        <ReceiveMessageResult>
          <Message><MessageId>mid</MessageId><ReceiptHandle>receiptHandle</ReceiptHandle><MD5OfBody>$md5OfBody</MD5OfBody><Body>$body</Body></Message>
        </ReceiveMessageResult>
        <ResponseMetadata><RequestId>b6633655-283d-45b4-aee4-4e84e0ae6afa</RequestId></ResponseMetadata>
      </ReceiveMessageResponse>
    """)
  }

  lazy val sqsReceiveMessageResponseFailure: HttpResponse =
    response().withStatusCode(500)
}
