package raft
package algebra.election

import cats.MonadError
import cats.effect._
import raft.algebra.event.EventsLogger
import raft.algebra.io.NetworkIO
import raft.model._

class BroadcastVoteImpl[F[_]: Timer: ContextShift: Concurrent, FF[_], Cmd](
  allState: RaftNodeState[F, Cmd],
  networkManager: NetworkIO[F, Cmd],
  eventLogger: EventsLogger[F, Cmd, _]
)(implicit F: MonadError[F, Throwable])
    extends BroadcastVote[F] {

  override def requestVotes: F[Map[String, F[Unit]]] = {
    for {
      tpe <- allState.serverTpe.get
      taskMap <- tpe match {
                  case _: Candidate => broadcastVoteReq
                  case _ => Map.empty[String, F[Unit]].pure[F]
                }
    } yield taskMap
  }

  private def broadcastVoteReq: F[Map[String, F[Unit]]] = {
    val nodeId = allState.config.nodeId
    for {
      persistent <- allState.persistent.get
      last       <- allState.logs.lastLog
      voteReq = VoteRequest(persistent.currentTerm, nodeId, last.map(_.idx), last.map(_.term))
    } yield {
      allState.config.peersId.map { target =>
        target -> eventLogger.voteRPCStarted(voteReq, target) *> sendReq(target, voteReq)
      }.toMap
    }
  }

  private def sendReq(targetPeer: String, request: VoteRequest): F[Unit] = {
    for {
      res <- networkManager.sendVoteRequest(targetPeer, request)
      _   <- processVote(targetPeer, res, request)
    } yield ()

  }

  private def processVote(targetPeer: String, res: VoteResponse, req: VoteRequest): F[Unit] = allState.serverTpeMutex {
    val conf = allState.config
    for {

      maybeCandidate <- allState.serverTpe.modify {
                         case c: Candidate =>
                           val nState = c.copy(receivedVotes = c.receivedVotes.updated(targetPeer, res.voteGranted))
                           nState -> Some(nState)
                         case other => other -> None
                       }
      _          <- eventLogger.voteRPCEnded(req, targetPeer, res)
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
                eventLogger.elected(persistent.currentTerm, lastLog.map(_.idx)) *>
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
                F.unit
              }
            case None => F.unit
          }
    } yield r
  }

}
