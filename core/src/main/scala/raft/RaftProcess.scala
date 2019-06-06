package raft

import cats._
import cats.effect.concurrent.{ MVar, Ref }
import cats.effect.{ Concurrent, ContextShift, Timer }
import fs2.Stream
import fs2.concurrent._
import raft.algebra.append._
import raft.algebra.client.{ ClientReadImpl, ClientWriteImpl }
import raft.algebra.election._
import raft.algebra.event.{ EventLogger, RPCTaskScheduler }
import raft.algebra.io.{ LogIO, NetworkIO }
import raft.algebra.{ RaftPollerImpl, StateMachine }
import raft.model._

trait RaftProcess[F[_], Cmd, State] {
  def startRaft: Stream[F, Unit]
  def api: RaftApi[F, Cmd, State]
}

import scala.concurrent.duration._

@SuppressWarnings(Array("org.wartremover.warts.Any"))
object RaftProcess {

  /**
    * Method used to start a RaftProcess, can be use to
    * restart Raft process eg. after server crash
    */
  def init[F[_]: Timer: Concurrent: ContextShift, Cmd: Eq, State](
    stateMachine: StateMachine[F, Cmd, State],
    clusterConfig: ClusterConfig,
    logIO: LogIO[F, Cmd],
    networkIO: NetworkIO[F, Cmd],
    eventLogger: EventLogger[F, Cmd, State],
    persistentIO: PersistentIO[F]
  ): F[RaftProcess[F, Cmd, State]] = {
    for {
      time <- Timer[F].clock.realTime(MILLISECONDS)
      initFollower = Follower(0, 0, time, None)
      serverTpeRef <- Ref.of[F, ServerType](initFollower)
      lock         <- MVar[F].of(())
      state = new RaftNodeState[F, Cmd] {
        override def config: ClusterConfig = clusterConfig

        override def persistent: PersistentIO[F] = persistentIO

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
      proc <- RaftProcess.apply(stateMachine, state, networkIO, appendHandler, voteHandler, eventLogger)
    } yield {
      proc
    }
  }

  def apply[F[_]: Concurrent: Timer: ContextShift, Cmd: Eq, State](
    stateMachine: StateMachine[F, Cmd, State],
    state: RaftNodeState[F, Cmd],
    networkIO: NetworkIO[F, Cmd],
    appendHandler: AppendRPCHandler[F, Cmd],
    voteHandler: VoteRPCHandler[F],
    eventLogger: EventLogger[F, Cmd, State]
  ): F[RaftProcess[F, Cmd, State]] = {
    val peers = state.config.peersId.toList
    for {
      committedTopic <- CustomTopics[F, Cmd]
      taskQueue <- peers
                    .traverse { nodeId =>
                      Queue
                        .bounded[F, F[Unit]](100)
                        .map(nodeId -> _)
                    }
                    .map(_.toMap)
    } yield {

      val scheduler = RPCTaskScheduler(taskQueue)
      val appendInitiator =
        new BroadcastAppendImpl(networkIO, stateMachine, state, committedTopic.publish1, eventLogger)

      val voteInitiator = new BroadcastVoteImpl(state, networkIO, eventLogger)

      val poller = new RaftPollerImpl(state, appendInitiator, voteInitiator)

      val clientWrite = new ClientWriteImpl[F, Cmd](
        state,
        appendInitiator,
        () => committedTopic.subscribe(100),
        scheduler,
        eventLogger
      )

      val clientRead = new ClientReadImpl[F, State](stateMachine, state, eventLogger)

      new RaftProcess[F, Cmd, State] {
        override def startRaft: Stream[F, Unit] = {
          // this is messy and potentially incorrect due to mixing
          // immutable data structure with concurrent queue

          val rpcTasks = taskQueue.values
            .foldLeft(Stream[F, Unit]()) {
              case (merged, queue) =>
                merged.merge(
                  queue.dequeue.evalMap(
                    _.recoverWith {
                      case err =>
                        eventLogger.errorLogs(s"Unexpected error when evaluating rpc tasks: ${err.getMessage}")
                    }
                  )
                )
            }

          poller.start
            .evalMap { tasks =>
              tasks.toList.traverse_ {
                case (nodeId, task) => scheduler.register(nodeId, task)
              }
            }
            .concurrently(rpcTasks)
            .recoverWith {
              case err =>
                Stream.eval(
                  eventLogger.errorLogs(s"Unexpected error on RaftProc: ${err.getMessage}")
                )
            }
        }

        override def api: RaftApi[F, Cmd, State] = new RaftApi[F, Cmd, State] {
          override def write(cmd: Cmd): F[WriteResponse] = clientWrite.write(cmd)

          override def read: F[ReadResponse[State]] = clientRead.read

          override def requestVote(req: VoteRequest): F[VoteResponse] = voteHandler.requestVote(req)

          override def requestAppend(req: AppendRequest[Cmd]): F[AppendResponse] =
            appendHandler.requestAppend(req)
        }
      }
    }
  }
}
