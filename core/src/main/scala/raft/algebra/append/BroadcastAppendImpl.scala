package raft
package algebra.append

import cats.MonadError
import cats.effect.{ ContextShift, Timer }
import fs2.concurrent.Topic
import org.slf4j.{ Logger, LoggerFactory }
import raft.algebra.StateMachine
import raft.algebra.event.EventLogger
import raft.algebra.io.NetworkIO
import raft.model._

import scala.concurrent.duration.MILLISECONDS

class BroadcastAppendImpl[F[_]: Timer: ContextShift, Cmd, State](
  networkManager: NetworkIO[F, Cmd],
  stateMachine: StateMachine[F, Cmd, State],
  allState: RaftNodeState[F, Cmd],
  committedTopic: Topic[F, Cmd],
  eLogger: EventLogger[F, Cmd, State]
)(implicit F: MonadError[F, Throwable])
    extends BroadcastAppend[F] {
  type Log = RaftLog[Cmd]

  private val logger: Logger = LoggerFactory.getLogger(s"${getClass.getSimpleName}.${allState.config.nodeId}")

  override def replicateLogs: F[Map[String, F[Unit]]] = {
    for {
      serverType <- allState.serverTpe.get
      reqs <- serverType match {
               case l: Leader => prepareRequest(l)
               case _ => Map.empty[String, AppendRequest[Cmd]].pure[F]
             }

    } yield {
      reqs.map {
        case (nodeId, req) => nodeId -> syncWithFollower(nodeId, req)
      }
    }
  }

  private def prepareRequest(leader: Leader): F[Map[String, AppendRequest[Cmd]]] = {

    for {
      persistent <- allState.persistent.get
      requests <- leader.nextIndices.toList.traverse {
                   case (nodeId, nextIdx) =>
                     for {
                       logsToSend <- allState.logs.takeFrom(nextIdx)
                       prevLog    <- allState.logs.getByIdx(nextIdx - 1)
                     } yield {
                       nodeId -> AppendRequest(
                         term         = persistent.currentTerm,
                         leaderId     = allState.config.nodeId,
                         prevLogIdx   = prevLog.map(_.idx),
                         prevLogTerm  = prevLog.map(_.term),
                         entries      = logsToSend,
                         leaderCommit = leader.commitIdx
                       )
                     }
                 }
    } yield requests.toMap
  }

  type LoR = Either[Unit, Unit]
  private def syncWithFollower(followerId: String, req: AppendRequest[Cmd]): F[Unit] = {

    // todo: we should not assume leader is still up-to-date
    // need to handle where term does not match
    def handleResponse(res: AppendResponse, leader: Leader): F[LoR] = res match {
      case AppendResponse(_, true) =>
        val updatedSt = req.entries.lastOption match {
          case Some(lastAppended) =>
            val updated = updateLeaderState(followerId, lastAppended)(leader)

            eLogger.leaderAppendSucceeded(req, followerId) *>
            allState.serverTpe.set(updated).as(updated)

          case None => leader.pure[F]
        }

        for {
          updated <- updatedSt
          _       <- findIdxToCommit(updated)
        } yield Either.right(())

      case AppendResponse(nodeTerm, false) =>
        for {
          persistent <- allState.persistent.get
          currentT = persistent.currentTerm
          r <- if (currentT < nodeTerm) {
                convertToFollow(nodeTerm).as[LoR](Right(()))
              } else {
                eLogger.leaderAppendRejected(req, followerId) *>
                appendFailed(followerId, leader).as[LoR](Left(()))
              }
        } yield r
    }

    def updateState: F[LoR] = {
      for {
        res <- networkManager.sendAppendRequest(followerId, req)
        r <- allState.serverTpeMutex {
              for {
                state <- allState.serverTpe.get
                result <- state match {
                           case l: Leader => handleResponse(res, l)
                           case _ => F.unit.map(_.asRight[Unit])
                         }
              } yield result
            }
      } yield r
    }

    // todo: Perform retry
    updateState.as(())
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
        persistent <- allState.persistent.get
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
      _ <- allState.serverTpe.set(leader.copy(commitIdx = newlyCommited.idx))
      _ <- stateMachine.execute(newlyCommited.command)
      _ <- committedTopic.publish1(newlyCommited.command)
      _ <- eLogger.logCommitted(newlyCommited.idx)
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
      _ <- allState.persistent.update(_.copy(currentTerm = newTerm))

      time <- Timer[F].clock.realTime(MILLISECONDS)
      _ <- allState.serverTpe.update { server =>
            Follower(server.commitIdx, server.lastApplied, time, None)
          }
    } yield ()

  }
}
