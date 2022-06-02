package com.ocado.ospnow.wms.pass4s.high

import cats.data.Chain
import cats.effect._
import cats.implicits._
import com.ocado.ospnow.wms.pass4s.core._
import fs2.Stream
import weaver.SimpleIOSuite

import scala.concurrent.duration._
import scala.reflect.runtime.universe._

object CancellableMessageProcessingBrokerTests extends SimpleIOSuite {
  trait Test

  final case class TestSource(name: String, override val cancelableMessageProcessing: Boolean) extends Source[Test] {
    override val capability: Type = typeOf[Test]
  }

  sealed trait State
  final object ProcessingStart extends State
  final object ProcessingFinish extends State
  final object MessageCommit extends State
  final case class MessageRollback(rollbackCause: RollbackCause) extends State

  val createBroker: IO[(Ref[IO, Chain[State]], Broker[IO, Test])] =
    Ref[IO].of(Chain.empty[State]).fproduct { stateLog =>
      Broker.fromConnector(new Connector[IO, Test] {
        override type Raw = Unit
        override val underlying: Unit = ()

        override def consumeBatched[R >: Test](source: Source[R]): Stream[IO, List[CommittableMessage[IO]]] =
          Stream(Message.Payload("s", Map()))
            .map(CommittableMessage.instance(_, stateLog.update(_ :+ MessageCommit), rc => stateLog.update(_ :+ MessageRollback(rc))))
            .map(List(_))

        override def produce[R >: Test](message: Message[R]): IO[Unit] = ???
      })
    }

  val cancelableSource: TestSource = TestSource("Test", cancelableMessageProcessing = true)
  val uncancelableSource: TestSource = TestSource("Test", cancelableMessageProcessing = false)

  test("cancelable source should break message processing and rollback message if canceled") {
    for {
      (stateLog, broker) <- createBroker
      deferred           <- Deferred[IO, Unit]
      _                  <- broker
                              .consumer(cancelableSource)
                              .consume(_ =>
                                stateLog.update(_ :+ ProcessingStart) *>
                                  deferred.complete(()) *> IO.sleep(100.millis) *> IO.cede *>
                                  stateLog.update(_ :+ ProcessingFinish)
                              )
                              .background
                              .surround(deferred.get)
      states             <- stateLog.get
    } yield expect(states.toList == List(ProcessingStart, MessageRollback(RollbackCause.Canceled)))
  }

  test("uncancelable source should continue message processing and commit message if canceled") {
    for {
      (stateLog, broker) <- createBroker
      deferred           <- Deferred[IO, Unit]
      _                  <- broker
                              .consumer(uncancelableSource)
                              .consume(_ =>
                                stateLog.update(_ :+ ProcessingStart) *>
                                  deferred.complete(()) *> IO.sleep(100.millis) *> IO.cede *>
                                  stateLog.update(_ :+ ProcessingFinish)
                              )
                              .background
                              .surround(deferred.get)
      states             <- stateLog.get
    } yield expect(states.toList == List(ProcessingStart, ProcessingFinish, MessageCommit))
  }

}
