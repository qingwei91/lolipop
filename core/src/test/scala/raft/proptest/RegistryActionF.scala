package raft
package proptest

import cats.free.Free
import cats._
import cats.effect._
import cats.effect.concurrent.Ref
import raft.model.{ CommandCommitted, NoLeader, RedirectTo }

import scala.concurrent.duration._

sealed trait RegistryActionF[A]
case object StartAll extends RegistryActionF[Unit]
case object StopAll extends RegistryActionF[Unit]
case class PutToReg(k: String, v: String) extends RegistryActionF[String]

object RegistryActionF {
  type RegistryProgram[A] = Free[RegistryActionF, A]

  def startAll = Free.liftF(StartAll)
  def stopAll  = Free.liftF(StopAll)

  def putKV(k: String, v: String) = Free.liftF(PutToReg(k, v))

  type RegistryRaft[F[_]] = RaftProcess[F, RegistryCmd, Map[String, String]]

  case class InternalState(finalizers: Map[String, IO[Unit]], runningNode: Set[String])

  @SuppressWarnings(Array("org.wartremover.warts.All"))
  def toIO(raftCluster: Map[String, RegistryRaft[IO]], states: Ref[IO, InternalState])(
    implicit cs: ContextShift[IO],
    tm: Timer[IO]
  ): RegistryActionF ~> IO =
    new (RegistryActionF ~> IO) {

      override def apply[A](fa: RegistryActionF[A]): IO[A] = fa match {
        case StartAll =>
          val startAllNodes = for {
            allocated <- raftCluster.unorderedTraverse(_.startRaft.allocated)
            finalizers = allocated.map {
              case (k, (_, release)) => k -> release
            }
            procs = allocated.map {
              case (k, (proc, _)) => k -> proc
            }
            _ <- states.update(_.copy(finalizers, finalizers.keySet))
            _ <- procs.unorderedTraverse(_.compile.lastOrError.start)
          } yield ()

          startAllNodes
        case StopAll =>
          val stopAllNodes = for {
            finalizers <- states.get.map(_.finalizers)
            _          <- finalizers.unorderedSequence
            _          <- states.update(_.copy(Map.empty, Set.empty))
          } yield ()
          stopAllNodes
        case PutToReg(k, v) =>
          /**
            * 1. pick one node and write
            * 2. If failed with leader redirect, pick the leader node
            * 3. If failed with other, back off and go back to 1
            */
          def writeToNode(nodeId: String): IO[Unit] = {
            raftCluster(nodeId).api.write(Put(k, v)).flatMap {
              case CommandCommitted => IO.unit
              case RedirectTo(nodeID) => IO.sleep(2.seconds) *> writeToNode(nodeID)
              case NoLeader => IO.sleep(2.seconds) *> writeToNode(nodeId)
            }
          }

          writeToNode(raftCluster.keySet.head).as(v)
      }
    }

}
