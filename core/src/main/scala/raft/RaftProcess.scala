package raft

import cats._
import cats.effect.concurrent.{ MVar, Ref }
import cats.effect.{ Concurrent, ContextShift, Timer }
import fs2.Stream
import fs2.concurrent.{ Queue, Topic }
import raft.algebra.append._
import raft.algebra.client.ClientIncomingImpl
import raft.algebra.election._
import raft.algebra.io.{ LogIO, NetworkIO }
import raft.algebra.{ RaftPollerImpl, StateMachine }
import raft.model._

trait RaftProcess[F[_], Cmd] {
  def startRaft: Stream[F, Unit]
  def api: RaftApi[F, Cmd]
}

import scala.concurrent.duration._

@SuppressWarnings(Array("org.wartremover.warts.Any"))
object RaftProcess {

  // simplest way to create a RaftProcess, it creates most of
  // the dependencies for you, the trade-off is that you have
  // less control over it, which is fine for prod server as it
  // is likely what you want
  // `initialCmd` is needed due to how fs2.concurrent.Topic work
  // todo: remove initialCmd by re-model the Topic to use
  //  Option[A]
  def simple[F[_]: Timer: Concurrent: ContextShift, FF[_], Cmd: Eq, State](
    stateMachine: StateMachine[F, Cmd, State],
    clusterConfig: ClusterConfig,
    logIO: LogIO[F, Cmd],
    networkIO: NetworkIO[F, RaftLog[Cmd]],
    initialCmd: Cmd
  )(implicit FParallel: Parallel[F, FF]): F[RaftProcess[F, Cmd]] = {
    for {
      time <- Timer[F].clock.realTime(MILLISECONDS)
      initFollower = Follower(0, 0, time, None)
      persistRef   <- Ref.of[F, Persistent](Persistent.init)
      serverTpeRef <- Ref.of[F, ServerType](initFollower)
      lock         <- MVar[F].of(())
      state = new RaftNodeState[F, Cmd] {
        override def config: ClusterConfig = clusterConfig

        override def persistent: Ref[F, Persistent] = persistRef

        override def serverTpe: Ref[F, ServerType] = serverTpeRef

        override def serverTpeLock: MVar[F, Unit] = lock

        override def logs: LogIO[F, Cmd] = logIO
      }
      appendHandler = new AppendRPCHandlerImpl(
        stateMachine,
        state
      )
      voteHandler = new VoteRPCHandlerImpl(state)
      proc <- RaftProcess.apply(stateMachine, state, networkIO, appendHandler, voteHandler, initialCmd)
    } yield {
      proc
    }
  }

  def apply[F[_]: Concurrent: Timer: ContextShift, FF[_], Cmd: Eq, State](
    stateMachine: StateMachine[F, Cmd, State],
    state: RaftNodeState[F, Cmd],
    networkIO: NetworkIO[F, RaftLog[Cmd]],
    appendHandler: AppendRPCHandler[F, RaftLog[Cmd]],
    voteHandler: VoteRPCHandler[F],
    initialCmd: Cmd
  )(implicit P: Parallel[F, FF]): F[RaftProcess[F, Cmd]] = {
    for {
      committedTopic       <- Topic[F, Cmd](initialCmd)
      logsReplicationTasks <- Queue.bounded[F, F[Unit]](100)
    } yield {

      val appendInitiator = new BroadcastAppendImpl[F, FF, Cmd, State](networkIO, stateMachine, state, committedTopic)

      val voteInitiator = new BroadcastVoteImpl[F, FF, Cmd](state, networkIO)

      val poller         = new RaftPollerImpl(state, appendInitiator, voteInitiator, logsReplicationTasks)
      val clientIncoming = new ClientIncomingImpl(state, appendInitiator, committedTopic, logsReplicationTasks)
      new RaftProcess[F, Cmd] {
        override def startRaft: Stream[F, Unit] = {
          val replicationTask = logsReplicationTasks.dequeue.evalMap(identity)
          poller.start.concurrently[F, Unit](replicationTask)
        }

        override def api: RaftApi[F, Cmd] = new RaftApi[F, Cmd] {
          override def incoming(cmd: Cmd): F[ClientResponse] = clientIncoming.incoming(cmd)

          override def requestVote(req: VoteRequest): F[VoteResponse] = voteHandler.requestVote(req)

          override def requestAppend(req: AppendRequest[RaftLog[Cmd]]): F[AppendResponse] =
            appendHandler.requestAppend(req)
        }
      }
    }
  }
}
