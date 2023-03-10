package com.ocadotechnology.pass4s.util

import cats.effect.IO
import cats.effect.Resource
import com.dimafeng.testcontainers.SingleContainer

object TestContainersUtils {

  def containerResource[T <: SingleContainer[_]](
    container: IO[T]
  ): Resource[IO, T] =
    Resource.fromAutoCloseable(container.flatTap(c => IO(c.start())))

}
