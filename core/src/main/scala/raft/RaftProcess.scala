package raft

import cats._
import cats.effect.{Concurrent, ContextShift, Resource, Timer}
import fs2.Stream
import fs2.concurrent._
import raft.algebra.append._
import raft.algebra.client.{ClientReadImpl, ClientWriteImpl}
import raft.algebra.election._
import raft.algebra.event.{EventsLogger, RPCTaskScheduler}
import raft.algebra.io.{LogsApi, MetadataIO, NetworkIO}
import raft.algebra.{RaftPollerImpl, StateMachine}
import raft.model._

import scala.concurrent.duration._
import scala.concurrent.TimeoutException

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

  def apply[F[_]: Concurrent: Timer: ContextShift, Cmd: Eq, Res](
    stateMachine: StateMachine[F, Cmd, Res],
    state: RaftNodeState[F, Cmd],
    networkIO: NetworkIO[F, Cmd],
    appendHandler: AppendRPCHandler[F, Cmd],
    voteHandler: VoteRPCHandler[F],
    eventLogger: EventsLogger[F, Cmd, Res]
  ): F[RaftProcess[F, Cmd, Res]] = {
    val peers = state.config.peersId.toList
    for {
      committedTopic <- CustomTopics[F, (Cmd, Res)]
      taskQueuePerPeer <- peers
                           .traverse { nodeId =>
                             Queue
                               .bounded[F, F[Unit]](100)
                               .map(nodeId -> _)
                           }
                           .map(_.toMap)
    } yield {

      val scheduler = RPCTaskScheduler(taskQueuePerPeer)
      val appendInitiator =
        new BroadcastAppendImpl(
          networkIO,
          stateMachine,
          state,
          (cmd: Cmd, res: Res) => committedTopic.publish1((cmd, res)),
          eventLogger
        )

      val voteInitiator = new BroadcastVoteImpl(state, networkIO, eventLogger)

      val poller = new RaftPollerImpl(state, appendInitiator, voteInitiator)

      val clientWrite = new ClientWriteImpl[F, Cmd, Res](
        state,
        appendInitiator,
        () => committedTopic.subscribe(100),
        scheduler,
        eventLogger
      )

      val clientRead = new ClientReadImpl[F, Cmd, Res](stateMachine, state, eventLogger)

      new RaftProcess[F, Cmd, Res] {
        override def startRaft: Resource[F, Stream[F, Unit]] = {

          val rpcTasks: Stream[F, Unit] = Stream(
            taskQueuePerPeer.values.toList.map { q =>
              q.dequeue.evalMap { task =>
                // TODO: another alternative is to cancel if there's new
                // task instead of timeout, not sure if that is better
                Concurrent
                  .timeout(task, 500.millis)
                  .recoverWith {
                    case _: TimeoutException =>
                      // todo: Missing details on which task that times
                      // out, we need a way tag a task with meta data
                      eventLogger.errorLogs("Task timed out")
                    case err =>
                      eventLogger.errorLogs(s"Unexpected error when evaluating rpc tasks: $err")
                  }
              }
            }: _*
          ).parJoinUnbounded

//          val rpcTasks = taskQueuePerPeer.values
//            .foldLeft(Stream[F, Unit]()) {
//              case (merged, queue) =>
//                merged.merge(
//                  queue.dequeue.evalMap(
//                    _.recoverWith {
//                      case err =>
//                        eventLogger.errorLogs(s"Unexpected error when evaluating rpc tasks: $err")
//                    }
//                  )
//                )
//            }

          val raftTask = poller.start
            .evalMap { pollerTasks =>
              pollerTasks.toList.traverse_ {
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

        override def api: RaftApi[F, Cmd, Res] = new RaftApi[F, Cmd, Res] {
          override def write(cmd: Cmd): F[ClientResponse[Res]] = clientWrite.write(cmd)

          override def requestVote(req: VoteRequest): F[VoteResponse] = voteHandler.requestVote(req)

          override def requestAppend(req: AppendRequest[Cmd]): F[AppendResponse] =
            appendHandler.requestAppend(req)

          override def staleRead(readCmd: Cmd): F[ClientResponse[Res]] = clientRead.staleRead(readCmd)
        }
      }
    }
  }
}
