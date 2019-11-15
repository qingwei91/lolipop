package raft
package algebra.append

import cats.MonadError
import cats.effect.Timer
import org.slf4j.LoggerFactory
import raft.algebra._
import raft.algebra.event.EventsLogger
import raft.model._

import scala.concurrent.duration._

@SuppressWarnings(Array("org.wartremover.warts.Throw"))
class AppendRPCHandlerImpl[F[_]: Timer, Cmd, State](
  val stateMachine: StateMachine[F, Cmd, State],
  val allState: RaftNodeState[F, Cmd],
  elogger: EventsLogger[F, Cmd, State]
)(implicit F: MonadError[F, Throwable])
    extends AppendRPCHandler[F, Cmd] {
  type Log = RaftLog[Cmd]

  private val logger = LoggerFactory.getLogger(s"${getClass.getSimpleName}.${allState.config.nodeId}")

  override def requestAppend(req: AppendRequest[Cmd]): F[AppendResponse] = allState.serverTpeMutex {
    for {
      time <- Timer[F].clock.realTime(MILLISECONDS)
      serverType <- allState.serverTpe.modify[ServerType] {
                     case f: Follower =>
                       val newF = f.copy(lastRPCTimeMillis = time)
                       newF -> newF
                     case c: Candidate =>
                       val newC = c.copy(lastRPCTimeMillis = time)
                       newC -> newC
                     case l: Leader => l -> l
                   }

      res <- serverType match {
              case f: Follower => followerServeAppend(req, f)
              case _: Candidate => candidateServeAppend(req)
              case _: Leader => leaderServeAppend(req)
            }
    } yield res
  }

  private def followerServeAppend(req: AppendRequest[Cmd], follower: Follower): F[AppendResponse] = {
    for {
      persistent <- allState.metadata.get
      r          <- handleReq(persistent, req, follower)
    } yield r
  }
  private def candidateServeAppend(req: AppendRequest[Cmd]): F[AppendResponse] = {
    for {
      persistent <- allState.metadata.get
      currentTerm = persistent.currentTerm
      toFollower  = req.term >= currentTerm
      r <- if (toFollower) {
            for {
              follower <- convertToFollower(req.term, req.leaderId)
              r        <- followerServeAppend(req, follower)
            } yield r
          } else {
            rejectAppend(currentTerm).pure[F]
          }
    } yield r
  }

  private def leaderServeAppend(req: AppendRequest[Cmd]): F[AppendResponse] = {
    for {
      persistent <- allState.metadata.get
      currentTerm = persistent.currentTerm
      toFollower  = req.term > currentTerm

      r <- if (toFollower) {
            for {
              follower <- convertToFollower(req.term, req.leaderId)
              r        <- followerServeAppend(req, follower)
            } yield r
          } else {
            rejectAppend(currentTerm).pure[F]
          }
    } yield r
  }

  private def handleReq(state: Metadata, req: AppendRequest[Cmd], follower: Follower): F[AppendResponse] = {
    import state._
    val leaderOutdated = req.term < currentTerm

    checkPrevLogsConsistency(req).flatMap { isConsistent =>
      val prevLogMisMatch = !isConsistent
      val res = true match {
        case `leaderOutdated` => rejectAppend(currentTerm).pure[F]
        case `prevLogMisMatch` => rejectAppend(currentTerm).pure[F]

        case _ =>
          allState.logs.overwrite(req.entries) *>
            commitAndExecCmd(req.leaderCommit, follower)
              .as(acceptAppend(currentTerm))
      }
      res.flatTap(r => elogger.appendRPCReplied(req, r))
    }
  }

  private def rejectAppend(term: Int) = AppendResponse(term, false)
  private def acceptAppend(term: Int) = AppendResponse(term, true)
  private def convertToFollower(newTerm: Int, leaderId: String): F[Follower] = {
    for {
      _       <- allState.metadata.update(_.copy(currentTerm = newTerm))
      rpcTime <- Timer[F].clock.realTime(MILLISECONDS)
      st <- allState.serverTpe.modify { s =>
             val newState = Follower(s.commitIdx, s.lastApplied, rpcTime, Some(leaderId))
             newState -> newState
           }
    } yield st
  }

  // This reflect log matching property, it's determined by induction
  private def checkPrevLogsConsistency(request: AppendRequest[Cmd]): F[Boolean] = {
    val prevIdx  = request.prevLogIdx
    val prevTerm = request.prevLogTerm
    (prevIdx, prevTerm) match {
      case (Some(idx), Some(term)) =>
        for {
          target <- allState.logs.getByIdx(idx)
        } yield {
          target.exists(_.term == term)
        }

      case (None, None) =>
        for {
          last <- allState.logs.lastLog
        } yield {

          val r = last.isEmpty
          if (!r) {
            logger.error(
              s"Unexpected case, leader ${request.leaderId} thought follower does not have log but last log is $last"
            )
          }
          r
        }
      case other =>
        logger.error(s"Broken constraint, prevIdx and prevTerm should be both absent or present, instead got $other")
        false.pure[F]
    }
  }

  private def commitAndExecCmd(leaderCommit: Int, follower: Follower): F[Unit] = {
    if (leaderCommit > follower.commitIdx) {

      val newCommitIdxF = for {
        last <- allState.logs.lastLog
      } yield {
        last.map(x => math.min(x.idx, leaderCommit))
      }

      for {
        maybeNewIdx <- newCommitIdxF
        _ <- maybeNewIdx match {
              case Some(newCommitIdx) =>
                for {
                  time <- Timer[F].clock.realTime(MILLISECONDS)
                  updatedFollower = follower.copy(commitIdx = newCommitIdx, lastRPCTimeMillis = time)
                  _ <- allState.serverTpe.set(updatedFollower)
                  _ <- applyLatestCmd(newCommitIdx, updatedFollower)
                } yield ()
              case None => F.unit
            }
      } yield ()

    } else F.unit
  }

  private def applyLatestCmd(committedIdx: Int, follower: Follower): F[Unit] = {
    if (follower.lastApplied == committedIdx) {
      F.unit
    } else if (follower.lastApplied < committedIdx) {
      for {
        maybLog <- allState.logs.getByIdx(committedIdx)
        Some(log) = maybLog
        st   <- stateMachine.execute(log.command)
        _    <- elogger.logCommittedAndExecuted(committedIdx, log.command, st)
        time <- Timer[F].clock.realTime(MILLISECONDS)
        _    <- allState.serverTpe.set(follower.copy(lastApplied = log.idx, lastRPCTimeMillis = time))
      } yield ()
    } else {
      F.raiseError[Unit](new RuntimeException("Unexpected: Last Applied is larger than committed, there's a bug"))
    }
  }

}
