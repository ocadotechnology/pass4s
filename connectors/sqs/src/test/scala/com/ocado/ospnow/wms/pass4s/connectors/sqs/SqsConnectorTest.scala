package com.ocado.ospnow.wms.pass4s.connectors.sqs

import weaver.SimpleIOSuite

object SqsConnectorTest extends SimpleIOSuite {
  val sqsUrl = "https://sqs.eu-west-1.amazonaws.com/52145425636/some-name"
  val urlWithoutPath = "https://sqs.eu-west-1.amazonaws.com"
  val invalidUrl = "some-name"

  def nameOfSqsEndpoint(url: String) = SqsEndpoint(SqsUrl(url)).name

  pureTest("SQS Url") {
    expect(nameOfSqsEndpoint(sqsUrl) == "some-name")
  }

  pureTest("Url without path") {
    expect(nameOfSqsEndpoint(urlWithoutPath) == urlWithoutPath)
  }

  pureTest("Invalid Url") {
    expect(nameOfSqsEndpoint(invalidUrl) == invalidUrl)
  }
}
