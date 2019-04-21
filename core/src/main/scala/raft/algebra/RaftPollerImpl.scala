package raft
package algebra

import cats.Monad
import cats.effect.{ Concurrent, Timer }
import fs2.Stream
import fs2.concurrent.Queue
import org.slf4j.LoggerFactory
import raft.algebra.append.BroadcastAppend
import raft.algebra.election.BroadcastVote
import raft.model.{ Candidate, Follower, Leader, NonLeader, RaftNodeState }

import scala.concurrent.duration._
import scala.util.Random

class RaftPollerImpl[F[_]: Monad: Concurrent, Cmd](
  allState: RaftNodeState[F, Cmd],
  broadcastAppend: BroadcastAppend[F],
  broadcastVote: BroadcastVote[F],
  replicationTask: Queue[F, F[Unit]]
)(implicit timer: Timer[F])
    extends RaftPoller[F] {
  val logger = LoggerFactory.getLogger(s"${getClass.getSimpleName}.${allState.config.nodeId}")

  val ConF = Concurrent[F]
  val F    = Monad[F]

  val timeoutBase = 100
  def start: Stream[F, Unit] = {
    Stream
      .awakeEvery[F](timeoutBase.millis)
      .evalMap[F, Unit] { _ =>
        replicationTask.enqueue1(tick)
      }
  }

  private def tick: F[Unit] = {
    for {
      serverTpe <- allState.serverTpe.get
      x <- serverTpe match {
            case _: Leader => leaderTick
            case _ => electionTick
          }
    } yield x
  }

  private def leaderTick: F[Unit] = broadcastAppend.replicateLogs

  private def electionTick: F[Unit] = {
    for {
      serverTpe <- allState.serverTpe.get
      _ <- serverTpe match {
            case f: Follower =>
              if (f.leaderId.isEmpty) {
                startElection(f)
              } else F.unit
            case c: Candidate => startElection(c)
            case _ => F.unit
          }
    } yield ()
  }
  private def startElection(nonLeader: NonLeader): F[Unit] = {
    for {
      timeInMillis <- timer.clock.realTime(MILLISECONDS)
      timeout        = Random.nextInt(timeoutBase * 3) + (timeoutBase * 2)
      timeoutReached = (timeInMillis - nonLeader.lastRPCTimeMillis) > timeout

      _ <- if (timeoutReached) {
            val newServerTpe = Candidate(nonLeader.commitIdx, nonLeader.lastApplied, timeInMillis, Map.empty)
            val selfId       = allState.config.nodeId

            for {
              _ <- allState.serverTpe.set(newServerTpe)
              updatedP <- allState.persistent.modify { p =>
                           val updated = p.copy(p.currentTerm + 1, votedFor = Some(selfId))
                           updated -> updated
                         }
              _ = logger.info(s"""
                             | Start new term ${updatedP.currentTerm}
                             | LastRPC = ${nonLeader.lastRPCTimeMillis}
                     """.stripMargin)
              _ <- broadcastVote.requestVotes
            } yield ()
          } else F.unit
    } yield {}

  }
}
