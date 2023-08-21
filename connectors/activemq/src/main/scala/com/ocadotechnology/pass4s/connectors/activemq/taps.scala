/*
 * Copyright 2021 Martin Krasser
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.ocadotechnology.pass4s.connectors.activemq

import akka.stream.FlowShape
import akka.stream.Graph
import akka.stream.Materializer
import akka.stream.OverflowStrategy
import akka.stream.QueueOfferResult
import akka.stream.SourceShape
import akka.stream.StreamDetachedException
import akka.stream.scaladsl.Keep
import akka.stream.scaladsl.Sink
import akka.stream.scaladsl.SinkQueueWithCancel
import akka.stream.scaladsl.Source
import akka.stream.scaladsl.SourceQueueWithComplete
import cats.effect.Async

import cats.effect.kernel.Resource.ExitCase
import cats.implicits._
import fs2.Pipe
import fs2.Stream

// Copied from https://github.com/krasserm/streamz due to the lack of CE3 support. https://github.com/krasserm/streamz/issues/85
private[activemq] object taps {

  implicit class AkkaSourceDsl[A, M](source: Graph[SourceShape[A], M]) {

    def toStream[F[_]: Async](onMaterialization: M => Unit = _ => ())(implicit materializer: Materializer): Stream[F, A] =
      akkaSourceToFs2Stream(source)(onMaterialization)
  }

  implicit class AkkaFlowDsl[A, B, M](flow: Graph[FlowShape[A, B], M]) {

    def toPipe[F[_]: Async](
      onMaterialization: M => Unit = _ => ()
    )(
      implicit materializer: Materializer
    ): Pipe[F, A, B] =
      akkaFlowToFs2Pipe(flow)(onMaterialization)

  }

  /** Converts an Akka Stream [[Graph]] of [[SourceShape]] to an FS2 [[Stream]]. The [[Graph]] is materialized when the [[Stream]]'s [[F]]
    * in run. The materialized value can be obtained with the `onMaterialization` callback.
    */
  private def akkaSourceToFs2Stream[F[_], A, M](
    source: Graph[SourceShape[A], M]
  )(
    onMaterialization: M => Unit
  )(
    implicit materializer: Materializer,
    F: Async[F]
  ): Stream[F, A] =
    Stream.force {
      F.delay {
        val (mat, subscriber) = Source.fromGraph(source).toMat(Sink.queue[A]())(Keep.both).run()
        onMaterialization(mat)
        subscriberStream[F, A](subscriber)
      }
    }

  /** Converts an Akka Stream [[Graph]] of [[FlowShape]] to an FS2 [[Pipe]]. The [[Graph]] is materialized when the [[Pipe]]'s [[F]] in run.
    * The materialized value can be obtained with the `onMaterialization` callback.
    */
  private def akkaFlowToFs2Pipe[F[_], A, B, M](
    flow: Graph[FlowShape[A, B], M]
  )(
    onMaterialization: M => Unit
  )(
    implicit materializer: Materializer,
    F: Async[F]
  ): Pipe[F, A, B] = { s =>
    Stream.force {
      F.delay {
        val src = Source.queue[A](0, OverflowStrategy.backpressure)
        val snk = Sink.queue[B]()
        val ((publisher, mat), subscriber) = src.viaMat(flow)(Keep.both).toMat(snk)(Keep.both).run()
        onMaterialization(mat)
        transformerStream[F, A, B](subscriber, publisher, s)
      }
    }
  }

  private def transformerStream[F[_]: Async, A, B](
    subscriber: SinkQueueWithCancel[B],
    publisher: SourceQueueWithComplete[A],
    stream: Stream[F, A]
  ): Stream[F, B] =
    subscriberStream[F, B](subscriber).concurrently(publisherStream[F, A](publisher, stream))

  private def publisherStream[F[_], A](
    publisher: SourceQueueWithComplete[A],
    stream: Stream[F, A]
  )(
    implicit F: Async[F]
  ): Stream[F, Unit] = {
    def publish(a: A): F[Option[Unit]] =
      Async[F]
        .fromFuture(F.delay(publisher.offer(a)))
        .flatMap {
          case QueueOfferResult.Enqueued       => ().some.pure[F]
          case QueueOfferResult.Failure(cause) => F.raiseError[Option[Unit]](cause)
          case QueueOfferResult.QueueClosed    => none[Unit].pure[F]
          case QueueOfferResult.Dropped        =>
            F.raiseError[Option[Unit]](new IllegalStateException("This should never happen because we use OverflowStrategy.backpressure"))
        }
        .recover {
          // This handles a race condition between `interruptWhen` and `publish`.
          // There's no guarantee that, when the akka sink is terminated, we will observe the
          // `interruptWhen` termination before calling publish one last time.
          // Such a call fails with StreamDetachedException
          case _: StreamDetachedException => none[Unit]
        }

    def watchCompletion: F[Unit] = Async[F].fromFuture(F.delay(publisher.watchCompletion())).void
    def fail(e: Throwable): F[Unit] = F.delay(publisher.fail(e)) >> watchCompletion
    def complete: F[Unit] = F.delay(publisher.complete()) >> watchCompletion

    stream
      .interruptWhen(watchCompletion.attempt)
      .evalMap(publish)
      .unNoneTerminate
      .onFinalizeCase {
        case ExitCase.Succeeded | ExitCase.Canceled => complete
        case ExitCase.Errored(e)                    => fail(e)
      }
  }

  private def subscriberStream[F[_], A](subscriber: SinkQueueWithCancel[A])(implicit F: Async[F]): Stream[F, A] = {
    val cancel = F.delay(subscriber.cancel())
    val pull = Async[F].fromFutureCancelable(F.delay((subscriber.pull(), cancel)))
    Stream.repeatEval(pull).unNoneTerminate.onFinalize(cancel)
  }

}
