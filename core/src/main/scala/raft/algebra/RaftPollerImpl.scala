package raft
package algebra

import cats.Monad
import cats.effect.{ Concurrent, Timer }
import fs2.Stream
import raft.algebra.append.BroadcastAppend
import raft.algebra.election.BroadcastVote
import raft.model._

import scala.concurrent.duration._
import scala.util.Random

class RaftPollerImpl[F[_]: Monad: Concurrent, Cmd](
  allState: RaftNodeState[F, Cmd],
  broadcastAppend: BroadcastAppend[F],
  broadcastVote: BroadcastVote[F]
)(implicit timer: Timer[F])
    extends RaftPoller[F] {

  val timeoutBase = 100
  def start: Stream[F, Map[String, F[Unit]]] = {
    Stream
      .awakeEvery[F](timeoutBase.millis)
      .evalMap[F, Map[String, F[Unit]]] { _ =>
        tick
      }
  }

  private def tick: F[Map[String, F[Unit]]] = {
    for {
      serverTpe <- allState.serverTpe.get
      x <- serverTpe match {
            case _: Leader => leaderTick
            case _ => electionTick
          }
    } yield x
  }

  private def leaderTick: F[Map[String, F[Unit]]] = broadcastAppend.replicateLogs

  private def electionTick: F[Map[String, F[Unit]]] = {
    for {
      serverTpe <- allState.serverTpe.get
      tasksMap <- serverTpe match {
                   case f: Follower if f.leaderId.isEmpty => startElection(f)
                   case c: Candidate => startElection(c)
                   case _ => Map.empty[String, F[Unit]].pure[F]
                 }
    } yield tasksMap
  }
  private def startElection(nonLeader: NonLeader): F[Map[String, F[Unit]]] = {
    for {
      timeInMillis <- timer.clock.realTime(MILLISECONDS)
      timeout        = Random.nextInt(timeoutBase * 3) + (timeoutBase)
      timeoutReached = (timeInMillis - nonLeader.lastRPCTimeMillis) > timeout

      votingReq <- if (timeoutReached) {
                    val newServerTpe = Candidate(nonLeader.commitIdx, nonLeader.lastApplied, timeInMillis, Map.empty)
                    val selfId       = allState.config.nodeId

                    for {
                      _ <- allState.serverTpe.set(newServerTpe)
                      _ <- allState.metadata.update { p =>
                            val updated = p.copy(p.currentTerm + 1, votedFor = Some(selfId))
                            updated
                          }
                      reqs <- broadcastVote.requestVotes
                    } yield reqs
                  } else Map.empty[String, F[Unit]].pure[F]
    } yield votingReq

  }
}
