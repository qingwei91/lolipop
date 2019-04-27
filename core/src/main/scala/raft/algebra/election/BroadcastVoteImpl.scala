package raft
package algebra.election

import cats.effect._
import cats.{ MonadError, Parallel }
import org.slf4j.LoggerFactory
import raft.algebra.io.NetworkIO
import raft.model._

class BroadcastVoteImpl[F[_]: Timer: ContextShift: Concurrent, FF[_], Cmd](
  allState: RaftNodeState[F, Cmd],
  networkManager: NetworkIO[F, RaftLog[Cmd]]
)(implicit Par: Parallel[F, FF], ME: MonadError[F, Throwable])
    extends BroadcastVote[F] {
  private val logger = LoggerFactory.getLogger(s"${getClass.getSimpleName}.${allState.config.nodeId}")

  override def requestVotes: F[Unit] = {
    for {
      tpe <- allState.serverTpe.get
      _ <- tpe match {
            case _: Candidate => broadcastVoteReq
            case _ => ME.unit
          }
    } yield ()
  }

  private def broadcastVoteReq: F[Unit] = {
    val nodeId = allState.config.nodeId
    for {
      persistent <- allState.persistent.get
      last       <- allState.logs.lastLog
      voteReq = VoteRequest(persistent.currentTerm, nodeId, last.map(_.idx), last.map(_.term))
      _ <- allState.config.peersId.toList.parTraverse { id =>
            sendReq(id, voteReq)
          }
    } yield ()
  }

  private def sendReq(id: String, request: VoteRequest): F[Unit] = {
    (for {
      res <- networkManager.sendVoteRequest(id, request)
      _   <- processVote(id, res, request)
    } yield ()).attempt
      .map {
        case Left(_) => logger.info(s"Failed to request vote from $id")
        case Right(_) => ()
      }
  }

  private def processVote(id: String, res: VoteResponse, req: VoteRequest): F[Unit] = allState.serverTpeMutex {
    val conf = allState.config
    for {
      maybeCandidate <- allState.serverTpe.modify {
                         case c: Candidate =>
                           if (res.voteGranted) {
                             logger.error(s"Received vote from $id (term: ${res.term}) for ${req.term}")
                           }

                           val nState = c.copy(receivedVotes = c.receivedVotes.updated(id, res.voteGranted))
                           nState -> Some(nState)
                         case other => other -> None
                       }
      persistent <- allState.persistent.get
      lastLog    <- allState.logs.lastLog

      // should we wrap this in critical region??
      // in theory we dont have to as the changes is convergent
      // meaning multi-threading should not impact the leader promotion
      // #monotonic
      r <- maybeCandidate match {
            case Some(cand) =>
              val totalVotes = cand.receivedVotes.count {
                case (_, voted) => voted
              }
              val enoughVotes = (totalVotes + 1) * 2 > conf.peersId.size + 1
              if (enoughVotes) {
                logger.error(s"Enough votes, converting to Leader for term ${persistent.currentTerm}")
                allState.serverTpe.update {
                  case c: Candidate =>
                    val nextLogIdx = lastLog.map(_.idx).getOrElse(0) + 1
                    Leader(
                      c.commitIdx,
                      c.lastApplied,
                      conf.peersId.map(_ -> nextLogIdx).toMap, // todo: might be wrong when no logs
                      conf.peersId.map(_ -> 0).toMap
                    )
                  case other => other
                }
              } else {
                ME.unit
              }
            case None => ME.unit
          }
    } yield r
  }

}
