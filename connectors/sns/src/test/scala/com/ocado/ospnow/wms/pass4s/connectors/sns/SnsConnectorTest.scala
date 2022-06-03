package com.ocadotechnology.pass4s.connectors.sns

import weaver.SimpleIOSuite

object SnsConnectorTest extends SimpleIOSuite {
  val arn = "arn:aws:sns:euw1:52452456:some-name"
  val invalidArn = "some-name"

  def nameOfSnsDestination(arn: String) = SnsDestination(SnsArn(arn)).name

  pureTest("SNS ARN") {
    expect(nameOfSnsDestination(arn) == "some-name")
  }

  pureTest("Invalid ARN") {
    expect(nameOfSnsDestination(invalidArn) == invalidArn)
  }
}
