package raft

import cats._
import cats.effect.concurrent.{ MVar, Ref }
import cats.effect.{ Concurrent, ContextShift, Timer }
import fs2.Stream
import fs2.concurrent._
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
  def simple[F[_]: Timer: Concurrent: ContextShift, Cmd: Eq, State](
    stateMachine: StateMachine[F, Cmd, State],
    clusterConfig: ClusterConfig,
    logIO: LogIO[F, Cmd],
    networkIO: NetworkIO[F, Cmd],
    eventLogger: EventLogger[F, Cmd, State]
  ): F[RaftProcess[F, Cmd]] = {
    for {
      time <- Timer[F].clock.realTime(MILLISECONDS)
      initFollower = Follower(0, 0, time, None)
      serverTpeRef <- Ref.of[F, ServerType](initFollower)
      persistentIO <- Ref.of[F, Persistent](Persistent.init)
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
  ): F[RaftProcess[F, Cmd]] = {
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

      val clientIncoming = new ClientIncomingImpl[F, Cmd](
        state,
        appendInitiator,
        () => committedTopic.subscribe(100),
        scheduler,
        eventLogger
      )

      new RaftProcess[F, Cmd] {
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

        override def api: RaftApi[F, Cmd] = new RaftApi[F, Cmd] {
          override def write(cmd: Cmd): F[ClientResponse] = clientIncoming.write(cmd)

          override def requestVote(req: VoteRequest): F[VoteResponse] = voteHandler.requestVote(req)

          override def requestAppend(req: AppendRequest[Cmd]): F[AppendResponse] =
            appendHandler.requestAppend(req)
        }
      }
    }
  }
}

/**
  * We are using a custom interface because the one from fs2 is not able to
  * express the act of subscribing the topic without consuming the message
  *
  * ie. subscribe method from fs2.concurrent.Topic return Stream[F, A] instead
  * of F[Stream[F, A]
  */
trait CustomTopics[F[_], A] {
  def publish1(a: A): F[Unit]
  def subscribe(maxQueued: Int): F[fs2.Stream[F, A]]
}

object CustomTopics {
  import cats.effect.concurrent.Ref

  @SuppressWarnings(Array("org.wartremover.warts.All"))
  def apply[F[_], A](implicit F: Concurrent[F]): F[CustomTopics[F, A]] =
    Ref
      .of[F, List[fs2.concurrent.Queue[F, A]]](List.empty)
      .map(
        cache =>
          new CustomTopics[F, A] {

            override def publish1(a: A): F[Unit] =
              cache.get.flatMap { subscribers =>
                subscribers.traverse { q =>
                  q.enqueue1(a)
                }.void
              }

            override def subscribe(maxQueued: Int): F[fs2.Stream[F, A]] = {
              def emptyQueue(maxQueued: Int): F[fs2.concurrent.Queue[F, A]] = {
                fs2.Stream
                  .bracket(fs2.concurrent.Queue.bounded[F, A](maxQueued))(
                    queue => cache.update(_.filter(_ ne queue))
                  )
                  .compile
                  .lastOrError
              }

              for {
                q <- emptyQueue(maxQueued)
                _ <- cache.update(_ :+ q)
              } yield {
                q.dequeue
              }
            }
        }
      )
}
