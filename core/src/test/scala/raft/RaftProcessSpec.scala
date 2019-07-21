package raft

import java.util.concurrent.Executors

import cats.effect._
import org.specs2.Specification
import org.specs2.specification.core.SpecStructure

import scala.concurrent.duration._
import scala.concurrent.ExecutionContext

@SuppressWarnings(Array("org.wartremover.warts.All"))
class RaftProcessSpec extends Specification {
  override def is: SpecStructure =
    s2"""
        RaftProcess is cancellable - $cancelRaftProcess
      """

  // todo: Improve this test, this is just a simple test to
  // ensure cancellation work by calling finalizer of Resource
  def cancelRaftProcess = {
    val executor                        = Executors.newFixedThreadPool(4)
    val ecToUse                         = ExecutionContext.fromExecutor(executor)
    implicit val ioCS: ContextShift[IO] = IO.contextShift(ecToUse)
    implicit val ioTM: Timer[IO]        = IO.timer(ecToUse)
    val raftTestDeps                    = RaftTestDeps[IO]

    val test = for {
      first                <- raftTestDeps.tasksIO.map(_.head)
      resourceAndFinalizer <- first.proc.startRaft.allocated
      (stream, finalizer) = resourceAndFinalizer
      finalizeLater       = IO.sleep(2.seconds) *> finalizer
      _ <- (stream.compile.lastOrError, finalizeLater).parMapN {
            case (_, _) => ()
          }
    } yield ()

    test.unsafeRunSync()
    success
  }
}
