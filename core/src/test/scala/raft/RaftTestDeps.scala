package raft

import cats.data._
import cats.effect.IO
import cats.effect.concurrent.{ MVar, Ref }
import fs2.concurrent.{ Queue, Topic }
import raft.algebra._
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
        monotime <- tm.clock.realTime(MILLISECONDS)
        tpe         = Follower(0, 0, monotime, None)
        clusterConf = ClusterConfig(i, clientIds - i)
        committedStream <- Topic[IO, String]("")
        clientReqQueue  <- Queue.bounded[IO, IO[Unit]](100)
        persist         <- Ref.of[IO, Persistent[RaftLog[String]]](Persistent.init)
        servTpe         <- Ref.of[IO, ServerType](tpe)
        lock            <- MVar[IO].of(())
        counter         <- Ref.of[IO, Int](0)
      } yield {
        val stateMachine = new TestStateMachine[IO]
        val state = TestState(
          clusterConf,
          persist,
          servTpe,
          lock,
          counter
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
    .map { data =>
      val appendResponders = data.map { case (i, _, _, append) => i -> append }.toNem
      val voteResponders   = data.map { case (i, vote, _, _) => i   -> vote }.toNem

      val network = new UnreliableNetwork(
        new InMemNetwork(appendResponders.toSortedMap, voteResponders.toSortedMap),
        shouldFail
      )

      data.map(_._3).map {
        case AllState(stateMachine, allState, committed, replicationTasks) =>
          val append = new BroadcastAppendImpl(
            network,
            stateMachine,
            allState,
            committed
          )
          val vote = new BroadcastVoteImpl[IO, IO.Par, String](
            allState,
            network
          )
          val poller = new RaftPollerImpl[IO, String](allState, append, vote, replicationTasks)
          val proc   = new RaftProcessImpl[IO](poller, replicationTasks)
          val client = new ClientIncomingImpl(allState, append, committed, replicationTasks)
          RaftTestComponents(proc, client, allState)
      }
    }
}

case class RaftTestComponents(
  proc: RaftProcess[IO],
  clientIncoming: ClientIncoming[IO, String],
  state: RaftNodeState[IO, String]
)
