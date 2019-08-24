package raft

import cats._
import cats.effect._
import cats.effect.concurrent.Ref
import fs2.Stream
import fs2.concurrent._
import raft.algebra.append._
import raft.algebra.client.{ ClientReadImpl, ClientWriteImpl }
import raft.algebra.election._
import raft.algebra.event.{ EventsLogger, RPCTaskScheduler }
import raft.algebra.io.{ LogsApi, MetadataIO, NetworkIO }
import raft.algebra.membership.MembershipStateMachine
import raft.algebra.{ RaftPollerImpl, StateMachine }
import raft.model._

/**
  * todo: Can be decouple startRaft and api?
  * Reality is that startRaft and api are related but not captured in type
  *
  * user should not start calling api before startRaft is evaluated
  *
  */
trait RaftProcess[F[_], Cmd, State] {
  def startRaft: Resource[F, Stream[F, Unit]]
  def api: RaftApi[F, Cmd, State]
}
@SuppressWarnings(Array("org.wartremover.warts.Any"))
object RaftProcess extends RaftProcessInstances {

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
  )(implicit membershipProjection: State => ClusterMembership): F[RaftProcess[F, Cmd, State]] = {
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

  // method for the lazy, this will construct a raft node that support
  // dynamic membership, we cannot fully encapsulate dynamic membership part
  // as it impacts things like persistent, network and logging
  def dynamicMembership[F[_]: Timer: Concurrent: ContextShift, Cmd: Eq, State](
    stateMachine: StateMachine[F, Cmd, State],
    clusterConfig: ClusterConfig,
    logsApi: LogsApi[F, Either[Cmd, AddMemberRequest]],
    networkIO: NetworkIO[F, Either[Cmd, AddMemberRequest]],
    eventLogger: EventsLogger[F, Either[Cmd, AddMemberRequest], (State, ClusterMembership)],
    metadataIO: MetadataIO[F]
  ): F[RaftProcess[F, Either[Cmd, AddMemberRequest], (State, ClusterMembership)]] = {

    implicit val projection: ((State, ClusterMembership)) => ClusterMembership = _._2

    for {
      membershipRef <- Ref[F].of(ClusterMembership(clusterConfig.nodeId, Set.empty))
      membershipStateMachine = new MembershipStateMachine(membershipRef)
      fullStateMachine       = StateMachine.compose(stateMachine, membershipStateMachine)
      state <- RaftNodeState.init(clusterConfig, metadataIO, logsApi)
      appendHandler = new AppendRPCHandlerImpl(
        fullStateMachine,
        state,
        eventLogger
      )
      voteHandler = new VoteRPCHandlerImpl(state, eventLogger)
      proc <- RaftProcess.apply(fullStateMachine, state, networkIO, appendHandler, voteHandler, eventLogger)
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
  )(
    implicit membershipProjection: State => ClusterMembership
  ): F[RaftProcess[F, Cmd, State]] = {
    for {
      committedTopic    <- CustomTopics[F, Cmd]
      singleQueueForAll <- Queue.bounded[F, F[Unit]](100)
    } yield {

      val scheduler = RPCTaskScheduler.singleQueue(singleQueueForAll)
      val appendInitiator =
        new BroadcastAppendImpl(networkIO, stateMachine, state, committedTopic.publish1, eventLogger)

      val voteInitiator = new BroadcastVoteImpl(state, networkIO, eventLogger, stateMachine)

      val poller = new RaftPollerImpl(state, appendInitiator, voteInitiator)

      val clientWrite = new ClientWriteImpl[F, Cmd](
        state,
        appendInitiator,
        () => committedTopic.subscribe(100),
        scheduler,
        eventLogger
      )

      val clientRead = new ClientReadImpl(stateMachine, state, eventLogger)

      new RaftProcess[F, Cmd, State] {
        override def startRaft: Resource[F, Stream[F, Unit]] = {

          val rpcTasks = singleQueueForAll.dequeue.parEvalMapUnordered(5)(_.recoverWith {
            case err => eventLogger.errorLogs(s"Unexpected error when evaluating rpc tasks: $err")
          })
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

trait RaftProcessInstances {
  implicit def deriveFunctor[F[_], Cmd](
    implicit apiFunctor: Functor[RaftApi[F, Cmd, ?]]
  ): Functor[RaftProcess[F, Cmd, ?]] = new Functor[RaftProcess[F, Cmd, ?]] {
    override def map[A, B](fa: RaftProcess[F, Cmd, A])(f: A => B): RaftProcess[F, Cmd, B] = new RaftProcess[F, Cmd, B] {
      override def startRaft: Resource[F, Stream[F, Unit]] = fa.startRaft
      override def api: RaftApi[F, Cmd, B]                 = apiFunctor.map(fa.api)(f)
    }
  }

  implicit def deriveContravariant[F[_], State](
    implicit apiContra: Contravariant[RaftApi[F, ?, State]]
  ): Contravariant[RaftProcess[F, ?, State]] = new Contravariant[RaftProcess[F, ?, State]] {
    override def contramap[A, B](fa: RaftProcess[F, A, State])(f: B => A): RaftProcess[F, B, State] =
      new RaftProcess[F, B, State] {
        override def startRaft: Resource[F, Stream[F, Unit]] = fa.startRaft

        override def api: RaftApi[F, B, State] = apiContra.contramap(fa.api)(f)
      }
  }
}
