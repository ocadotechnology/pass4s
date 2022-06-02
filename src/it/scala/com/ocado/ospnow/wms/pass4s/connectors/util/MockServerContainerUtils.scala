package com.ocado.ospnow.wms.pass4s.connectors.util

import cats.effect.IO
import cats.effect.Resource
import com.dimafeng.testcontainers.MockServerContainer
import org.mockserver.client.MockServerClient

object MockServerContainerUtils {

  val actualVersion: String = classOf[MockServerClient].getPackage.getImplementationVersion

  def containerResource(): Resource[IO, MockServerContainer] =
    TestContainersUtils.containerResource(IO(MockServerContainer.Def(version = actualVersion).createContainer()))

  def createClient(mockServerContainer: MockServerContainer): MockServerClient =
    new MockServerClient(mockServerContainer.host, mockServerContainer.serverPort)

}
