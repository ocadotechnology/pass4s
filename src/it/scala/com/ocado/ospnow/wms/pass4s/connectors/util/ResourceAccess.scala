package com.ocadotechnology.pass4s.connectors.util

import cats.effect.IO
import cats.effect.Resource
import cats.effect.kernel.Ref
import cats.effect.std.Semaphore
import cats.~>

/* A way to manage resources manually, while also ensuring only up to one copy of a given resource is allocated */
trait ResourceAccess { self =>
  def shutdown: IO[Unit]
  def start: IO[Unit]

  def mapK(f: IO ~> IO): ResourceAccess =
    new ResourceAccess {
      def shutdown: IO[Unit] = f(self.shutdown)
      val start: IO[Unit] = f(self.start)
    }

}

object ResourceAccess {

  //the cleanup of this resource ensures that the underlying one has been shut down
  def fromResource[A](resource: Resource[IO, A]): Resource[IO, ResourceAccess] =
    Resource.suspend {
      //lock is necessary to make sure `start` is atomic
      Semaphore[IO](1).flatMap { lock =>
        Ref[IO].of(Option.empty[IO[Unit]]).map { ref =>
          val access = new ResourceAccess {
            val shutdown: IO[Unit] =
              ref.modify {
                case None            => (None, IO.raiseError(new Throwable("Can't close - there's no resource allocated")))
                case Some(finalizer) => (None, finalizer)
              }.flatten

            val start: IO[Unit] =
              ref
                .get
                .flatMap {
                  case Some(_) => IO.raiseError(new Throwable("Can't start - resource already allocated!"))
                  case None    => resource.allocated
                }
                .flatMap {
                  case (_, finalizer) => ref.set(Some(finalizer))
                }
          }

          Resource.make(IO.pure(access.mapK(λ[IO ~> IO](v => lock.permit.surround(v)))).flatTap(_.start))(_.shutdown.attempt.void)
        }
      }
    }

}
