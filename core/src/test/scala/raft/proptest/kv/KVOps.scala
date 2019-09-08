package raft.proptest.kv

import cats._
import cats.effect.concurrent.Ref
import cats.effect.{ Concurrent, ContextShift, Sync, Timer }
import cats.instances.list._
import cats.kernel.Eq
import cats.syntax.apply._
import cats.syntax.applicativeError._
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.syntax.traverse._
import raft.RaftProcess
import raft.algebra.StateMachine
import raft.algebra.append.{ AppendRPCHandler, AppendRPCHandlerImpl }
import raft.algebra.client.{ ClientRead, ClientWrite }
import raft.algebra.election.{ VoteRPCHandler, VoteRPCHandlerImpl }
import raft.algebra.event.EventsLogger
import raft.model._
import raft.setup.{ InMemNetwork, TestLogsIO, TestMetadata }
import raft.util.Slf4jLogger

import scala.concurrent.TimeoutException
import scala.concurrent.duration.FiniteDuration

sealed trait KVOps[V]
case class Put[V](k: String, v: V) extends KVOps[V]
case class Get[V](k: String) extends KVOps[V]
case class Delete[V](k: String) extends KVOps[V]

@SuppressWarnings(Array("org.wartremover.warts.All"))
object KVOps {
  type InnerSt      = Map[String, String]
  type KVCmd        = KVOps[String]
  type KVHist       = List[Event[KVCmd, String]]
  type KVRaft[F[_]] = ClientWrite[F, KVCmd] with ClientRead[F, InnerSt]

  implicit val eqKVCmd: Eq[KVCmd] = Eq.fromUniversalEquals[KVCmd]

  case class Stuff[F[_]](
    sm: StateMachine[F, KVCmd, InnerSt],
    nodeS: RaftNodeState[F, KVCmd],
    append: AppendRPCHandler[F, KVCmd],
    vote: VoteRPCHandler[F],
    logger: EventsLogger[F, KVCmd, InnerSt]
  )

  def stateMachine[F[_]](ref: Ref[F, InnerSt]): StateMachine[F, KVCmd, InnerSt] =
    StateMachine.fromRef(ref) { st =>
      {
        case Put(k, v) => st.updated(k, v)
        case Delete(k) => st - k
        case Get(_) => st
      }
    }

  def setupCluster[F[_]: Sync: Concurrent: ContextShift: Timer](
    allIds: Map[String, StateMachine[F, KVCmd, InnerSt]]
  ): F[Map[String, RaftProcess[F, KVCmd, InnerSt]]] = {

    val allRaftNodes = allIds.toList.traverse[F, (String, Stuff[F])] {
      case (id, sm) =>
        val clusterConf = ClusterConfig(id, allIds.keySet - id)
        for {
          refLog      <- Ref[F].of(Seq.empty[RaftLog[KVCmd]])
          refMetadata <- Ref[F].of(Metadata(0, None))
          logsIO     = new TestLogsIO[F, KVCmd](refLog)
          metadataIO = new TestMetadata[F](refMetadata)
          logger     = new Slf4jLogger[F, KVCmd, InnerSt](clusterConf.nodeId)
          state <- RaftNodeState.init[F, KVCmd](clusterConf, metadataIO, logsIO)
          appendHandler = new AppendRPCHandlerImpl(
            sm,
            state,
            logger
          ): AppendRPCHandler[F, KVCmd]
          voteHandler = new VoteRPCHandlerImpl(state, logger): VoteRPCHandler[F]
        } yield {
          clusterConf.nodeId -> Stuff(
            sm,
            state,
            appendHandler,
            voteHandler,
            logger
          )
        }

    }

    for {
      handlers <- allRaftNodes
      handlersMap = handlers.toMap
      appends     = handlersMap.mapValues(_.append)
      votes       = handlersMap.mapValues(_.vote)
      network     = new InMemNetwork[F, KVCmd, InnerSt](appends, votes)
      allNodes <- handlers.traverse[F, (String, RaftProcess[F, KVCmd, InnerSt])] {
                   case (id, node) =>
                     RaftProcess(
                       node.sm,
                       node.nodeS,
                       network,
                       node.append,
                       node.vote,
                       node.logger
                     ).map(id -> _)
                 }
    } yield {
      allNodes.toMap
    }
  }

  def client[F[_]](cluster: Map[String, KVRaft[F]], sleepTime: FiniteDuration, timeout: FiniteDuration)(
    implicit F: MonadError[F, Throwable],
    t: Timer[F],
    con: Concurrent[F]
  ): KVClient[F, String] = new KVClient[F, String] {

    override def get(s: String): F[DSResult[String]] = {
      def loop(api: KVRaft[F]): F[InnerSt] = {
        api.read.flatMap {
          case RedirectTo(nodeID) => loop(cluster(nodeID))
          case NoLeader => t.sleep(sleepTime) *> loop(api)
          case Read(v) => F.pure(v)
        }
      }

      val (_, api) = cluster.head
      loop(api)
        .timeout(timeout)
        .map[DSResult[String]](st => ReadOK(st(s)))
        .recover {
          case _: TimeoutException => Timeout
          case o: Throwable => Failed(o)
        }
    }

    override def put(k: String, v: String): F[DSResult[String]] = {
      def loop(api: KVRaft[F]): F[Unit] = {
        api.write(Put(k, v)).flatMap {
          case RedirectTo(nodeID) => loop(cluster(nodeID))
          case NoLeader => t.sleep(sleepTime) *> loop(api)
          case CommandCommitted => Monad[F].unit
        }
      }
      val (_, api) = cluster.head
      loop(api)
        .timeout(timeout)
        .map[DSResult[String]](_ => WriteOK)
        .recover {
          case _: TimeoutException => Timeout
          case o: Throwable => Failed(o)
        }

    }
  }

  def execute[F[_]: Monad](ops: KVCmd, client: KVClient[F, String], threadId: String): F[KVHist] =
    ops match {
      case op @ Put(k, v) =>
        client.put(k, v).map { res =>
          List(Invoke[KVCmd, String](threadId, op), Ret[KVCmd, String](threadId, res))
        }
      case op @ Get(k) =>
        client.get(k).map { res =>
          List(
            Invoke[KVCmd, String](threadId, op),
            Ret[KVCmd, String](threadId, res)
          )
        }
      case Delete(_) => Monad[F].pure(Nil)
    }

  implicit class TimeoutUnlawful[F[_], A](fa: F[A])(implicit F: MonadError[F, Throwable]) {
    def timeout(duration: FiniteDuration)(implicit tm: Timer[F], cs: Concurrent[F]): F[A] = {
      cs.race(
          fa,
          tm.sleep(duration)
        )
        .flatMap {
          case Left(a) => F.pure(a)
          case Right(_) => F.raiseError(new TimeoutException(s"Didn't finished in $duration"))
        }
    }
  }

}

trait KVClient[F[_], V] {
  def get(s: String): F[DSResult[V]]
  def put(s: String, v: V): F[DSResult[V]]
}
