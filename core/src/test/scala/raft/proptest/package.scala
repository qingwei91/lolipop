package raft

import java.io.{ File, PrintWriter }

import cats.{ Eq, Monad, MonadError, Parallel }
import cats.effect.concurrent.Ref
import cats.effect.{ Concurrent, ContextShift, IO, Sync, Timer }
import io.circe.Json
import io.circe.parser.parse
import org.slf4j.LoggerFactory
import raft.algebra.StateMachine
import raft.algebra.io.NetworkIO
import raft.algebra.append.{ AppendRPCHandler, AppendRPCHandlerImpl }
import raft.algebra.election.{ VoteRPCHandler, VoteRPCHandlerImpl }
import raft.model.{ ClusterConfig, Metadata, RaftLog, RaftNodeState }
import raft.setup.{ InMemNetwork, TestLogsIO, InMemMetadata }
import raft.util.Slf4jLogger

import scala.concurrent.TimeoutException
import scala.concurrent.duration._
import scala.io.Source

package object proptest {
  val logger = LoggerFactory.getLogger("PropTest")
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
    def time(name: String)(implicit t: Timer[F]): F[A] = {
      for {
        start <- t.clock.realTime(MILLISECONDS)
        a     <- fa
        end   <- t.clock.realTime(MILLISECONDS)
      } yield {
        logger.info(s"$name took ${end - start} millis")
        a
      }
    }
  }

  def setupCluster[F[_]: Sync: Concurrent: ContextShift: Timer, Cmd: Eq, St](
    allIds: Map[String, StateMachine[F, Cmd, St]],
    injectNetwork: NetworkIO[F, Cmd] => NetworkIO[F, Cmd]
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
          metadataIO = new InMemMetadata[F](refMetadata)
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
      network     = injectNetwork(new InMemNetwork[F, Cmd, St](appends, votes))
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

  def setupCluster[F[_]: Sync: Concurrent: ContextShift: Timer, Cmd: Eq, St](
    allIds: Map[String, StateMachine[F, Cmd, St]]
  ): F[Map[String, RaftProcess[F, Cmd, St]]] = {
    setupCluster(allIds, identity)
  }

  def interleave[A, B](operationsA: List[A], operationsB: List[B]): List[Either[A, B]] = {
    val sizeA = operationsA.size
    val sizeB = operationsB.size
    
    def inner[I, J](
      largeChunk: List[I],
      smallChunk: List[J],
      ratio: Int,
      acc: List[Either[I, J]]
    ): List[Either[I, J]] = {
      if (largeChunk.isEmpty) {
        acc ::: smallChunk.map(Right(_))
      } else {
        smallChunk match {
          case h :: tail =>
            val newAcc = (acc ::: largeChunk.take(ratio).map(Left(_))) :+ Right(h)
            inner(largeChunk.drop(ratio), tail, ratio, newAcc)
          case Nil =>
            acc ::: largeChunk.map(Left(_))

        }
      }
    }
    if (sizeA > sizeB) {
      val interleaveRatio = Math.max(sizeA / sizeB, 1)
      inner(operationsA, operationsB, interleaveRatio, Nil)
    } else {
      val interleaveRatio = Math.max(sizeB / sizeA, 1)
      inner(operationsB, operationsA, interleaveRatio, Nil).map(_.swap)
    }
  }

  def parTest[F[_]: Monad: Parallel, R](
    n: Int
  )(fn: Int => F[R]): F[List[R]] = {
    (1 to n).toList.parTraverse { id =>
      fn(id)
    }
  }

  def time[A](name: String)(a: () => A): A = {
    val start = System.currentTimeMillis()
    val r     = a()
    val end   = System.currentTimeMillis()
    logger.info(s"$name took ${end - start} millis")
    r
  }

  def toFile(file: File)(json: Json): IO[Unit] = {
    IO(new PrintWriter(file)).bracket(w => IO(w.println(json.spaces2)))(w => IO(w.close()))
  }

  def fromFile(file: File): IO[Json] = {
    IO(Source.fromFile(file)).bracket(src => IO.fromEither(parse(src.mkString)))(src => IO(src.close()))
  }
}
