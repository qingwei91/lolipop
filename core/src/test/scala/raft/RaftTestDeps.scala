package raft

import cats.data._
import cats.effect.IO
import cats.effect.concurrent.{ MVar, Ref }
import fs2.concurrent.{ Queue, Topic }
import raft.algebra.append._
import raft.algebra.client._
import raft.algebra.election._
import raft.model._
import raft.setup._

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._

@SuppressWarnings(Array("org.wartremover.warts.All"))
class RaftTestDeps(ec: ExecutionContext, shouldFail: (String, String) => Boolean = (_, _) => false) {

  implicit val cs = IO.contextShift(ec)
  implicit val tm = IO.timer(ec)

  val clientIds = {
    NonEmptySet.of(0, 1 to 4: _*).map(_.toString)
  }

  case class AllState(
    stateMachine: TestStateMachine[IO],
    state: RaftNodeState[IO, String],
    committed: Topic[IO, String],
    clientReq: Queue[IO, IO[Unit]]
  )

  val tasksIO: IO[NonEmptyList[RaftTestComponents]] = clientIds.toNonEmptyList
    .traverse { i =>
      for {
        time <- tm.clock.realTime(MILLISECONDS)
        tpe = Follower(0, 0, time, None)
        committedStream <- Topic[IO, String]("")
        clientReqQueue  <- Queue.bounded[IO, IO[Unit]](100)
        persist         <- Ref.of[IO, Persistent](Persistent.init)
        servTpe         <- Ref.of[IO, ServerType](tpe)
        lock            <- MVar[IO].of(())
        baseLog         <- Ref.of[IO, Seq[RaftLog[String]]](Seq.empty)
      } yield {
        val clusterConf  = ClusterConfig(i, clientIds - i)
        val stateMachine = new TestStateMachine[IO]
        val state = TestState(
          clusterConf,
          persist,
          servTpe,
          lock,
          new TestLogsIO(baseLog)
        )

        val allState = AllState(stateMachine, state, committedStream, clientReqQueue)
        val append = new AppendRPCHandlerImpl(
          stateMachine,
          state
        )
        val vote = new VoteRPCHandlerImpl(state)
        (i, vote, allState, append)
      }
    }
    .flatMap { data =>
      val appendResponders = data.map { case (i, _, _, append) => i -> append }.toNem
      val voteResponders   = data.map { case (i, vote, _, _) => i   -> vote }.toNem

      val network = new UnreliableNetwork(
        new InMemNetwork(appendResponders.toSortedMap, voteResponders.toSortedMap),
        shouldFail
      )

      data.traverse {
        case (_, voteHandler, AllState(stateMachine, allState, _, _), appendHandler) =>
          for {
            proc <- RaftProcess(
                     stateMachine,
                     allState,
                     network,
                     appendHandler,
                     voteHandler,
                     ""
                   )
          } yield {
            RaftTestComponents(proc, proc.api, allState)
          }
      }
    }
}

case class RaftTestComponents(
  proc: RaftProcess[IO, String],
  clientIncoming: ClientIncoming[IO, String],
  state: RaftNodeState[IO, String]
)
