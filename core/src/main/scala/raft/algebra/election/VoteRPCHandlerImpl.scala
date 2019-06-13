package raft
package algebra.election

import cats.Monad
import cats.effect.Timer
import raft.algebra.event.EventLogger
import raft.model._

import scala.concurrent.duration._

class VoteRPCHandlerImpl[F[_]: Monad: Timer, Cmd](
  allState: RaftNodeState[F, Cmd],
  eventLogger: EventLogger[F, Cmd, _]
) extends VoteRPCHandler[F] {
  type Log = RaftLog[Cmd]

  override def requestVote(req: VoteRequest): F[VoteResponse] = {
    allState.serverTpeMutex {
      for {
        time <- Timer[F].clock.realTime(MILLISECONDS)
        _ <- allState.serverTpe.update {
              case f: Follower => f.copy(lastRPCTimeMillis  = time)
              case c: Candidate => c.copy(lastRPCTimeMillis = time)
              case l: Leader => l
            }
        pState  <- allState.persistent.get
        lastLog <- allState.logs.lastLog

        currentTerm  = pState.currentTerm
        sameTerm     = req.term == currentTerm
        higherTerm   = req.term > currentTerm
        hasVote      = (sameTerm && voteAvailable(pState.votedFor, req.candidateID)) || higherTerm
        canGrantVote = hasVote && candidateUpToDate(req, lastLog)

        res = if (canGrantVote) {
          VoteResponse(currentTerm, voteGranted = true)
        } else {
          VoteResponse(currentTerm, voteGranted = false)
        }

        _ <- eventLogger.voteRPCReplied(req, res)
        _ <- if (res.voteGranted) {
              for {
                _ <- allState.persistent.update(x => x.copy(currentTerm = req.term, votedFor = Some(req.candidateID)))
                _ <- allState.serverTpe.update(
                      s => Follower(s.commitIdx, s.lastApplied, time, Some(req.candidateID))
                    )
              } yield ()

            } else {
              Monad[F].unit
            }
      } yield res
    }
  }

  def voteAvailable(votedFor: Option[String], candidateId: String): Boolean = {
    votedFor.isEmpty || votedFor.contains(candidateId)
  }

  def candidateUpToDate(req: VoteRequest, lastLog: Option[Log]): Boolean = {
    (lastLog, req.lastLogIdx, req.lastLogTerm) match {

      case (Some(latestLocal), Some(lastLogIdx), Some(lastLogTerm)) =>
        val candidateHigherTerm = lastLogTerm > latestLocal.term
        val candidateHigherIdx  = lastLogTerm == latestLocal.term && lastLogIdx >= latestLocal.idx

        candidateHigherTerm || candidateHigherIdx

      case (None, None, None) =>
        // candidate and local node agree that local does not have log
        true
      case _ => false
    }
  }

}
