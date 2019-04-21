package raft
package algebra.append

import cats.effect.{ ContextShift, Timer }
import cats.{ MonadError, Parallel }
import fs2.concurrent.Topic
import org.slf4j.{ Logger, LoggerFactory }
import raft.algebra.StateMachine
import raft.algebra.io.NetworkIO
import raft.model._

import scala.concurrent.duration.MILLISECONDS

class BroadcastAppendImpl[F[_]: Timer: ContextShift, FA[_], Cmd, State](
  networkManager: NetworkIO[F, RaftLog[Cmd]],
  stateMachine: StateMachine[F, Cmd, State],
  allState: RaftNodeState[F, Cmd],
  committedTopic: Topic[F, Cmd]
)(implicit parallel: Parallel[F, FA], F: MonadError[F, Throwable])
    extends BroadcastAppend[F] {
  type Log = RaftLog[Cmd]

  // todo: try monadic pure logger
  private val logger: Logger = LoggerFactory.getLogger(s"${getClass.getSimpleName}.${allState.config.nodeId}")

  override def replicateLogs: F[Unit] = {
    for {
      // 1. Get state and prepare req
      serverType <- allState.serverTpe.get
      reqs <- serverType match {
               case l: Leader => prepareRequest(l)
               case _ => Map.empty[String, AppendRequest[Log]].pure[F]
             }
      // 2. Get State and send request
      _ <- reqs.toList.parTraverse {
            case (nodeId, req) => syncWithFollower(nodeId, req)
          }

    } yield ()

  }

  private def prepareRequest(leader: Leader): F[Map[String, AppendRequest[Log]]] = {

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
  private def syncWithFollower(nodeId: String, req: AppendRequest[Log]): F[Unit] = {

    // todo: we should not assume leader is still up-to-date
    // need to handle where term does not match
    def handleResponse(res: AppendResponse, leader: Leader): F[LoR] = res match {
      case AppendResponse(_, true) =>
        val updatedSt = req.entries.lastOption match {
          case Some(lastAppended) =>
            logger.error(s"Successfully sync to $nodeId up to ${lastAppended.idx}")
            val updated = updateLeaderState(nodeId, lastAppended)(leader)

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
                appendFailed(nodeId, leader).as[LoR](Left(()))
              }
        } yield r
    }

    def updateState: F[LoR] = {
      for {
        res <- networkManager.sendAppendRequest(nodeId, req)
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

    updateState.attempt
      .map {
        case Left(_) => logger.info(s"Failed to append log for $nodeId")
        case Right(_) => ()
      }
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
      logger.info(s"Prepare to commit ${leader.commitIdx + 1}")
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
    } yield {
      logger.error(s"Committed... $newlyCommited")
    }
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
