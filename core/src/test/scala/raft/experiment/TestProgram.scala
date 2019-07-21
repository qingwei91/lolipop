package raft.experiment

import cats._
import cats.effect._
import cats.effect.concurrent.Ref
import cats.free._
import cats.implicits._
import raft.RaftProcess
import raft.model._

import scala.concurrent.duration._

/**
  * Goal: Better way to test Raft protocol
  *
  * Write
  * Read
  */
sealed trait TestProgram[A]

case class StartNode(nodeId: String) extends TestProgram[Unit]
case class KillNode(nodeId: String) extends TestProgram[Unit]
case class Command(cmd: String) extends TestProgram[Boolean]
case object Read_ extends TestProgram[String]

object TestProgram {
  import Free._
  def start(nodeId: String) = liftF(StartNode(nodeId))
  def kill(nodeId: String)  = liftF(KillNode(nodeId))
  def cmd(cmd: String)      = liftF(Command(cmd))
  val read                  = liftF(Read_)

  @SuppressWarnings(
    Array(
      "org.wartremover.warts.Any",
      "org.wartremover.warts.TraversableOps"
    )
  )
  def interpreter(
    cluster: Map[String, RaftProcess[IO, String, String]],
    startedTask: Ref[IO, Map[String, Fiber[IO, Unit]]]
  )(implicit CS: ContextShift[IO], TM: Timer[IO]): TestProgram ~> IO = new (TestProgram ~> IO) {

    def sendCmd(command: Command, nodeId: Option[String]): IO[Boolean] = {
      val targetNode = nodeId match {
        case Some(id) => cluster(id)
        case None => cluster.values.head
      }
      targetNode.api.write(command.cmd).flatMap {
        case CommandCommitted => true.pure[IO]
        case NoLeader => false.pure[IO]
        case RedirectTo(nodeID) => sendCmd(command, Some(nodeID))
      }
    }

    def readState(nodeId: Option[String]): IO[String] = {
      val targetNode = nodeId match {
        case Some(id) => cluster(id)
        case None => cluster.values.head
      }

      targetNode.api.read.flatMap {
        case Read(s) => s.pure[IO]
        case NoLeader => IO.sleep(1.seconds) *> readState(None)
        case RedirectTo(nodeID) => readState(Some(nodeID))
      }
    }

    override def apply[A](fa: TestProgram[A]): IO[A] = {

      fa match {
        case StartNode(id) =>
          for {
            started <- cluster(id).startRaft.use(_.compile.lastOrError.start)
            _       <- startedTask.update(_.updated(id, started))
          } yield ()

        case KillNode(id) =>
          for {
            targetNode <- startedTask.get.map(_.get(id))
            _ <- targetNode match {
                  case Some(node) => node.cancel *> startedTask.update(_ - id)
                  case None => IO.unit
                }
          } yield ()

        case cmd: Command => sendCmd(cmd, None)
        case Read_ => readState(None)
      }
    }
  }
}
