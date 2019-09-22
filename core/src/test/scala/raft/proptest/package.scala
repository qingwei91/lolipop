package raft

import cats.{ Eq, MonadError }
import cats.effect.concurrent.Ref
import cats.effect.{ Concurrent, ContextShift, Sync, Timer }
import raft.algebra.StateMachine
import raft.algebra.append.{ AppendRPCHandler, AppendRPCHandlerImpl }
import raft.algebra.election.{ VoteRPCHandler, VoteRPCHandlerImpl }
import raft.model.{ ClusterConfig, Metadata, RaftLog, RaftNodeState }
import raft.setup.{ InMemNetwork, TestLogsIO, TestMetadata }
import raft.util.Slf4jLogger

import scala.concurrent.TimeoutException
import scala.concurrent.duration.FiniteDuration

package object proptest {
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

  def setupCluster[F[_]: Sync: Concurrent: ContextShift: Timer, Cmd: Eq, St](
    allIds: Map[String, StateMachine[F, Cmd, St]]
  ): F[Map[String, RaftProcess[F, Cmd, St]]] = {

    // The whole construction is split into 2 steps
    // because for in-mem RaftNode we need to 1st create all
    // dependencies of a Node before creating an InMemNetwork
    // in other words, the creation of In-Mem RaftNode is
    // inter-dependent with other nodes
    // this problem does not manifest in real networked setting because
    // each real node will have some stable reference (eg. IP, HostName)

    val allRaftNodes = allIds.toList.traverse[F, (String, Stuff[F, Cmd, St])] {
      case (id, sm) =>
        val clusterConf = ClusterConfig(id, allIds.keySet - id)
        for {
          refLog      <- Ref[F].of(Seq.empty[RaftLog[Cmd]])
          refMetadata <- Ref[F].of(Metadata(0, None))
          logsIO     = new TestLogsIO[F, Cmd](refLog)
          metadataIO = new TestMetadata[F](refMetadata)
          logger     = new Slf4jLogger[F, Cmd, St](clusterConf.nodeId)
          state <- RaftNodeState.init[F, Cmd](clusterConf, metadataIO, logsIO)
          appendHandler = new AppendRPCHandlerImpl(
            sm,
            state,
            logger
          ): AppendRPCHandler[F, Cmd]
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
      network     = new InMemNetwork[F, Cmd, St](appends, votes)
      allNodes <- handlers.traverse[F, (String, RaftProcess[F, Cmd, St])] {
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

}
