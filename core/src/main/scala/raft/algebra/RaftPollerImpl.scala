package raft
package algebra

import cats.Monad
import cats.effect.{ Concurrent, Timer }
import fs2.Stream
import raft.algebra.append.BroadcastAppend
import raft.algebra.election.BroadcastVote
import raft.model._
import ClusterMembership._
import raft.algebra.event.EventsLogger

import scala.concurrent.duration._
import scala.util.Random

class RaftPollerImpl[F[_]: Monad: Concurrent, Cmd, State](
  allState: RaftNodeState[F, Cmd],
  broadcastAppend: BroadcastAppend[F],
  broadcastVote: BroadcastVote[F],
  queryState: QueryState[F, State],
  eventLogger: EventsLogger[F, Cmd, State]
)(implicit timer: Timer[F], stateToMembership: State => ClusterMembership)
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
      timeout        = Random.nextInt(timeoutBase) + (timeoutBase * 5)
      timeoutReached = (timeInMillis - nonLeader.lastRPCTimeMillis) > timeout
      currentCluster <- queryState.getCurrent.map(_.getMembership)
      peersId = currentCluster.peersId
      metadata <- allState.metadata.get
      lastLog  <- allState.logs.lastLog
      votingReq <- if (timeoutReached) {
                    val selfId        = allState.nodeId
                    val receivedVotes = Map(selfId -> true)

                    val totalVotes = receivedVotes.count {
                      case (_, voted) => voted
                    }
                    val enoughVotes = totalVotes * 2 > peersId.size + 1
                    val updateTerm = allState.metadata.update { p =>
                      val updated = p.copy(p.currentTerm + 1, votedFor = Some(selfId))
                      updated
                    }

                    if (enoughVotes) {

                      // this happens only when we have 1 node
                      val becomeLeader = allState.serverTpe.set {
                        val nextLogIdx = lastLog.map(_.idx).getOrElse(0) + 1
                        Leader(
                          nonLeader.commitIdx,
                          nonLeader.lastApplied,
                          peersId.map(_ -> nextLogIdx).toMap, // todo: using nextLogIdx might be wrong when there is no existing logs
                          peersId.map(_ -> 0).toMap
                        )
                      }
                      for {
                        _ <- eventLogger.elected(metadata.currentTerm, lastLog.map(_.idx))
                        _ <- becomeLeader
                        _ <- updateTerm
                      } yield { Map.empty[String, F[Unit]] }

                    } else {
                      val cand = Candidate(nonLeader.commitIdx, nonLeader.lastApplied, timeInMillis, receivedVotes)
                      for {
                        _    <- allState.serverTpe.set(cand)
                        _    <- updateTerm
                        reqs <- broadcastVote.requestVotes
                      } yield reqs
                    }
                  } else Map.empty[String, F[Unit]].pure[F]
    } yield votingReq

  }
}
