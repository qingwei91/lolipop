package raft
package algebra.append

import cats.MonadError
import cats.effect.{ ContextShift, Timer }
import org.slf4j.{ Logger, LoggerFactory }
import raft.algebra.ChangeState
import raft.algebra.event.EventsLogger
import raft.algebra.io.NetworkIO
import raft.model._

import scala.concurrent.duration.MILLISECONDS

class BroadcastAppendImpl[F[_]: Timer: ContextShift, Cmd, State](
  networkManager: NetworkIO[F, Cmd],
  stateMachine: ChangeState[F, Cmd, State],
  allState: RaftNodeState[F, Cmd],
  publishCommittedEvent: Cmd => F[Unit],
  eLogger: EventsLogger[F, Cmd, State]
)(implicit F: MonadError[F, Throwable])
    extends BroadcastAppend[F] {
  type Log = RaftLog[Cmd]

  private val logger: Logger = LoggerFactory.getLogger(s"${getClass.getSimpleName}.${allState.config.nodeId}")

  override def replicateLogs: F[Map[String, F[Unit]]] = {
    allState.config.peersId
      .map { pId =>
        pId -> taskPerPeer(pId)
      }
      .toMap
      .pure[F]
  }

  private def taskPerPeer(peerId: String): F[Unit] = {
    def prepReq(l: Leader) = {
      for {
        persistent <- allState.metadata.get
        nextIdx = l.nextIndices(peerId)
        logsToSend <- allState.logs.takeFrom(nextIdx)
        prevLog    <- allState.logs.getByIdx(nextIdx - 1)
      } yield {
        AppendRequest(
          term         = persistent.currentTerm,
          leaderId     = allState.config.nodeId,
          prevLogIdx   = prevLog.map(_.idx),
          prevLogTerm  = prevLog.map(_.term),
          entries      = logsToSend,
          leaderCommit = l.commitIdx
        )
      }
    }

    def handleResponse(res: AppendResponse, leader: Leader, req: AppendRequest[Cmd]): F[Unit] = {
      res match {
        case AppendResponse(_, true) =>
          val updatedSt = req.entries.lastOption match {
            case Some(lastAppended) =>
              val updated = updateLeaderState(peerId, lastAppended)(leader)

              allState.serverTpe.set(updated).as(updated)

            case None => leader.pure[F]
          }

          for {
            updated <- updatedSt
            _       <- findIdxToCommit(updated)
          } yield ()

        case AppendResponse(nodeTerm, false) =>
          for {
            persistent <- allState.metadata.get
            currentT = persistent.currentTerm
            r <- if (currentT < nodeTerm) {
                  convertToFollow(nodeTerm)
                } else {
                  appendFailed(peerId, leader)
                }
          } yield r
      }
    }

    for {
      serverType <- allState.serverTpe.get
      _ <- serverType match {
            case l: Leader =>
              for {
                req <- prepReq(l)
                _   <- eLogger.appendRPCStarted(req, peerId)
                res <- networkManager.sendAppendRequest(peerId, req)
                r <- allState.serverTpeMutex {
                      for {
                        state <- allState.serverTpe.get
                        result <- state match {
                                   case l: Leader =>
                                     eLogger.appendRPCEnded(req, res) *>
                                       handleResponse(res, l, req)
                                   case _ => F.unit
                                 }
                      } yield result
                    }
              } yield r
            case _ => F.unit
          }
    } yield ()
  }

  private def updateLeaderState(nodeId: String, lastAppended: Log)(
    leader: Leader
  ): Leader = {
    import leader._

    val updatedNext  = nextIndices.updated(nodeId, lastAppended.idx + 1)
    val updatedMatch = matchIndices.updated(nodeId, lastAppended.idx)
    val updatedLst   = leader.copy(nextIndices = updatedNext, matchIndices = updatedMatch)
    updatedLst
  }

  private def findIdxToCommit(leader: Leader): F[Unit] = {

    val matchIdx = leader.matchIndices
    val replicatedNode = matchIdx.filter {
      case (_, matched) => matched > leader.commitIdx
    }

    val canCommit = (replicatedNode.size + 1) * 2 > matchIdx.size + 1

    if (canCommit) {
      for {
        maybeLog   <- allState.logs.getByIdx(leader.commitIdx + 1)
        persistent <- allState.metadata.get
        _ <- maybeLog match {
              case Some(log) =>
                val termMatched = log.term == persistent.currentTerm

                if (termMatched) {
                  commitLog(log, leader)
                } else {
                  F.unit
                }

              case None =>
                logger.error(s"Cannot find committed log, commitIdx=${leader.commitIdx + 1}")
                F.unit
            }
      } yield ()
    } else {
      F.unit
    }
  }

  private def commitLog(newlyCommited: Log, leader: Leader): F[Unit] = {
    for {
      _  <- allState.serverTpe.set(leader.copy(commitIdx = newlyCommited.idx))
      st <- stateMachine.execute(newlyCommited.command)
      _  <- publishCommittedEvent(newlyCommited.command)
      _  <- eLogger.logCommittedAndExecuted(newlyCommited.idx, newlyCommited.command, st)
    } yield ()
  }

  private def appendFailed(nodeId: String, leader: Leader): F[Unit] = {
    allState.serverTpe.set {
      val nextDecrement = leader.nextIndices(nodeId) - 1
      leader.copy(nextIndices = leader.nextIndices.updated(nodeId, nextDecrement))
    }
  }

  private def convertToFollow(newTerm: Int): F[Unit] = {
    for {
      _ <- allState.metadata.update(_.copy(currentTerm = newTerm))

      time <- Timer[F].clock.realTime(MILLISECONDS)
      _ <- allState.serverTpe.update { server =>
            Follower(server.commitIdx, server.lastApplied, time, None)
          }
    } yield ()

  }
}
