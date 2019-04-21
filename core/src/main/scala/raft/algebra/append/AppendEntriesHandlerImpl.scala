package raft
package algebra.append

import cats.MonadError
import cats.effect.Timer
import org.slf4j.LoggerFactory
import raft.algebra._
import raft.model._

import scala.concurrent.duration._

class AppendRPCHandlerImpl[F[_]: Timer, Cmd, State](
  val stateMachine: StateMachine[F, Cmd, State],
  val allState: RaftNodeState[F, Cmd]
)(implicit F: MonadError[F, Throwable])
    extends AppendRPCHandler[F, RaftLog[Cmd]] {
  type Log = RaftLog[Cmd]

  private val logger = LoggerFactory.getLogger(s"${getClass.getSimpleName}.${allState.config.nodeId}")

  override def requestAppend(req: AppendRequest[RaftLog[Cmd]]): F[AppendResponse] = {
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

  private def followerServeAppend(req: AppendRequest[Log], follower: Follower): F[AppendResponse] = {
    for {
      res <- allState.serverTpeMutex(
              for {
                persistent <- allState.persistent.get
                r          <- handleReq(persistent, req, follower)
              } yield r
            )
    } yield {
      res
    }
  }
  private def candidateServeAppend(req: AppendRequest[Log]): F[AppendResponse] = {
    for {
      persistent <- allState.persistent.get
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

  private def leaderServeAppend(req: AppendRequest[Log]): F[AppendResponse] = {
    for {
      persistent <- allState.persistent.get
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

  private def handleReq(state: Persistent[Log], req: AppendRequest[Log], follower: Follower): F[AppendResponse] = {
    import state._
    val leaderOutdated  = req.term < currentTerm
    val prevLogMisMatch = !checkPrevLogsConsistency(logs, req)

    true match {
      case `leaderOutdated` =>
        logger.error("Leader outdated, rejecting request")
        rejectAppend(currentTerm).pure[F]

      case `prevLogMisMatch` =>
        rejectAppend(currentTerm).pure[F]

      case _ =>
        reconcileLogs(req.entries, logs) *>
          commitAndExecCmd(req.leaderCommit, req.entries, follower)
            .as {
              if (req.entries.nonEmpty) {
                logger.info(s"Append accepted ${req.entries} from ${req.leaderId}")
              }
              acceptAppend(currentTerm)
            }
    }

  }

  private def rejectAppend(term: Int) = AppendResponse(term, false)
  private def acceptAppend(term: Int) = AppendResponse(term, true)
  private def convertToFollower(newTerm: Int, leaderId: String): F[Follower] = {
    for {
      _       <- allState.persistent.update(_.copy(currentTerm = newTerm))
      rpcTime <- Timer[F].clock.realTime(MILLISECONDS)
      st <- allState.serverTpe.modify { s =>
             val newState = Follower(s.commitIdx, s.lastApplied, rpcTime, Some(leaderId))
             newState -> newState
           }
    } yield st
  }

  // This reflect log matching property, it's determined by induction
  private def checkPrevLogsConsistency(logs: Seq[Log], request: AppendRequest[Log]): Boolean = {
    val prevIdx  = request.prevLogIdx
    val prevTerm = request.prevLogTerm
    (prevIdx, prevTerm) match {
      case (Some(idx), Some(term)) =>
        logs
          .collectFirst {
            case log if log.idx == idx => log.term == term
          }
          .getOrElse(false)
      case (None, None) =>
        val r = logs.isEmpty
        if (!r) {
          logger.error(s"Unexpected case, leader ${request.leaderId} thought follower does not have log but $logs")
        }
        r
      case other =>
        logger.error(s"Broken constraint, prevIdx and prevTerm should be both absent or present, instead got $other")
        false
    }
  }

  /**
    * Merge logs from leader with local logs by idx favor leader
    * logs when idx conflict, maintains logs order by idx
    */
  private def reconcileLogs(leaderLogs: Vector[Log], serverLog: Vector[Log]): F[Unit] = {

    // Todo: copying all logs might be too slow
    // It might be solved by log compaction or we need better algebra to
    // describe remove and append

    leaderLogs.toList match {
      case firstNew :: _ =>
        // only keep local logs that's not included in server logs
        val trimmedServerLog = serverLog.takeWhile(_.idx != firstNew.idx)
        allState.persistent.update { st =>
          st.copy(logs = trimmedServerLog ++ leaderLogs)
        }
      case Nil =>
        logger.debug("Received leader heartbeat")
        F.unit
    }
  }

  private def commitAndExecCmd(leaderCommit: Int, newEntries: Seq[Log], follower: Follower): F[Unit] = {
    logger.debug(s"Attempt to commit from leader $leaderCommit -- $newEntries")
    if (leaderCommit > follower.commitIdx) {

      val newCommitIdxF = allState.persistent.get
        .map { persist =>
          val localLogs = persist.logs
          val latestLocalIdx = localLogs.lastOption.map { lastLog =>
            math.min(lastLog.idx, leaderCommit)
          }
          latestLocalIdx
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
                } yield {
                  logger.info(s"Applied idx=$newCommitIdx")
                }
              case None => F.unit
            }
      } yield ()

    } else F.unit
  }

  private def applyLatestCmd(idxToApply: Int, follower: Follower): F[Unit] = {
    if (follower.lastApplied == idxToApply) {
      F.unit
    } else {
      for {
        logs <- allState.persistent.get.map(_.logs)
        Some(log) = logs.find(_.idx == idxToApply)
        _    <- stateMachine.execute(log.command)
        time <- Timer[F].clock.realTime(MILLISECONDS)
        _    <- allState.serverTpe.set(follower.copy(lastApplied = log.idx, lastRPCTimeMillis = time))
      } yield ()
    }
  }

}
