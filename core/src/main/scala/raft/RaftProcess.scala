package raft

import cats._
import cats.effect.{ Concurrent, ContextShift, Resource, Timer }
import fs2.Stream
import fs2.concurrent._
import raft.algebra.append._
import raft.algebra.client.{ ClientReadImpl, ClientWriteImpl }
import raft.algebra.election._
import raft.algebra.event.{ EventsLogger, RPCTaskScheduler }
import raft.algebra.io.{ LogsApi, MetadataIO, NetworkIO }
import raft.algebra.{ RaftPollerImpl, StateMachine }
import raft.model._

trait RaftProcess[F[_], Cmd, State] {

  def startRaft: Resource[F, Stream[F, Unit]]
  def api: RaftApi[F, Cmd, State]
}

@SuppressWarnings(Array("org.wartremover.warts.Any"))
object RaftProcess {

  /**
    * Method used to start a RaftProcess, can be use to
    * restart Raft process eg. after server crash
    */
  def init[F[_]: Timer: Concurrent: ContextShift, Cmd: Eq, State](
    stateMachine: StateMachine[F, Cmd, State],
    clusterConfig: ClusterConfig,
    logsApi: LogsApi[F, Cmd],
    networkIO: NetworkIO[F, Cmd],
    eventLogger: EventsLogger[F, Cmd, State],
    metadataIO: MetadataIO[F]
  ): F[RaftProcess[F, Cmd, State]] = {
    for {
      state <- RaftNodeState.init(clusterConfig, metadataIO, logsApi)
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
    eventLogger: EventsLogger[F, Cmd, State]
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
        override def startRaft: Resource[F, Stream[F, Unit]] = {

          val rpcTasks = taskQueue.values
            .foldLeft(Stream[F, Unit]()) {
              case (merged, queue) =>
                merged.merge(
                  queue.dequeue.evalMap(
                    _.recoverWith {
                      case err =>
                        eventLogger.errorLogs(s"Unexpected error when evaluating rpc tasks: $err")
                    }
                  )
                )
            }

          val raftTask = poller.start
            .evalMap { heartBeatTask =>
              heartBeatTask.toList.traverse_ {
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

          val interruptible = for {
            interruption <- Queue.bounded[F, Boolean](1)
          } yield {
            raftTask.interruptWhen(interruption.dequeue) -> interruption
          }

          Resource
            .make(interruptible) {
              case (_, interupt) =>
                interupt.enqueue1(true) *> eventLogger.processTerminated
            }
            .map(_._1)
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
