package raft

import cats._
import cats.effect.concurrent.{ MVar, Ref }
import cats.effect.{ Concurrent, ContextShift, Timer }
import fs2.Stream
import fs2.concurrent.{ Queue, Topic }
import raft.algebra.append._
import raft.algebra.client.ClientIncomingImpl
import raft.algebra.election._
import raft.algebra.event.{ EventLogger, RPCTaskScheduler }
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
    networkIO: NetworkIO[F, Cmd],
    persistentIO: Ref[F, Persistent],
    initialCmd: Cmd,
    eventLogger: EventLogger[F, Cmd, State]
  )(implicit FParallel: Parallel[F, FF]): F[RaftProcess[F, Cmd]] = {
    for {
      time <- Timer[F].clock.realTime(MILLISECONDS)
      initFollower = Follower(0, 0, time, None)
      serverTpeRef <- Ref.of[F, ServerType](initFollower)
      lock         <- MVar[F].of(())
      state = new RaftNodeState[F, Cmd] {
        override def config: ClusterConfig = clusterConfig

        override def persistent: Ref[F, Persistent] = persistentIO

        override def serverTpe: Ref[F, ServerType] = serverTpeRef

        override def serverTpeLock: MVar[F, Unit] = lock

        override def logs: LogIO[F, Cmd] = logIO
      }
      appendHandler = new AppendRPCHandlerImpl(
        stateMachine,
        state,
        eventLogger
      )
      voteHandler = new VoteRPCHandlerImpl(state, eventLogger)
      proc <- RaftProcess.apply(stateMachine, state, networkIO, appendHandler, voteHandler, initialCmd, eventLogger)
    } yield {
      proc
    }
  }

  def apply[F[_]: Concurrent: Timer: ContextShift, FF[_], Cmd: Eq, State](
    stateMachine: StateMachine[F, Cmd, State],
    state: RaftNodeState[F, Cmd],
    networkIO: NetworkIO[F, Cmd],
    appendHandler: AppendRPCHandler[F, Cmd],
    voteHandler: VoteRPCHandler[F],
    initialCmd: Cmd,
    eventLogger: EventLogger[F, Cmd, State]
  )(implicit P: Parallel[F, FF]): F[RaftProcess[F, Cmd]] = {
    val peers = state.config.peersId.toList
    for {
      committedTopic <- Topic[F, Cmd](initialCmd)
      taskQueue <- peers
                    .traverse { nodeId =>
                      Queue
                        .bounded[F, F[Unit]](100)
                        .map(nodeId -> _)
                    }
                    .map(_.toMap)
      scheduler = RPCTaskScheduler(taskQueue)
    } yield {

      val appendInitiator = new BroadcastAppendImpl(networkIO, stateMachine, state, committedTopic, eventLogger)

      val voteInitiator = new BroadcastVoteImpl(state, networkIO, eventLogger)

      val poller = new RaftPollerImpl(state, appendInitiator, voteInitiator)

      val clientIncoming = new ClientIncomingImpl(state, appendInitiator, committedTopic, scheduler, eventLogger)

      new RaftProcess[F, Cmd] {
        override def startRaft: Stream[F, Unit] = {
          // this is messy and potentially incorrect due to mixing
          // immutable data structure with concurrent queue

          val rpcTasks = Stream(
            taskQueue.map {
              case (_, queue) =>
                queue.dequeue.evalMap(identity)
            }.toList: _*
          ).parJoin(peers.size + 5)

          poller.start
            .evalMap { tasks =>
              tasks.toList.traverse_ {
                case (nodeId, task) => scheduler.register(nodeId, task)
              }
            }
            .concurrently(rpcTasks)
        }

        override def api: RaftApi[F, Cmd] = new RaftApi[F, Cmd] {
          override def incoming(cmd: Cmd): F[ClientResponse] = clientIncoming.incoming(cmd)

          override def requestVote(req: VoteRequest): F[VoteResponse] = voteHandler.requestVote(req)

          override def requestAppend(req: AppendRequest[Cmd]): F[AppendResponse] =
            appendHandler.requestAppend(req)
        }
      }
    }
  }
}
