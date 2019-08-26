package raft
package algebra
package election

import cats.MonadError
import cats.effect._
import raft.algebra.event.EventsLogger
import raft.algebra.io.NetworkIO
import raft.model._
import ClusterMembership._

class BroadcastVoteImpl[F[_]: Timer: ContextShift: Concurrent, FF[_], Cmd, State](
  allState: RaftNodeState[F, Cmd],
  networkManager: NetworkIO[F, Cmd],
  eventLogger: EventsLogger[F, Cmd, State],
  queryState: QueryState[F, State]
)(implicit F: MonadError[F, Throwable], stateToMembership: State => ClusterMembership)
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
    val nodeId = allState.nodeId
    for {
      persistent <- allState.metadata.get
      last       <- allState.logs.lastLog
      voteReq = VoteRequest(persistent.currentTerm, nodeId, last.map(_.idx), last.map(_.term))
      state <- queryState.getCurrent
    } yield {
      stateToMembership(state).peersId.map { target =>
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
    for {

      maybeCandidate <- allState.serverTpe.modify {
                         case c: Candidate =>
                           val nState = c.copy(receivedVotes = c.receivedVotes.updated(targetPeer, res.voteGranted))
                           nState -> Some(nState)
                         case other => other -> None
                       }
      _        <- eventLogger.voteRPCEnded(req, targetPeer, res)
      metadata <- allState.metadata.get
      lastLog  <- allState.logs.lastLog

      currentCluster <- queryState.getCurrent.map(_.getMembership)
      peersId = currentCluster.peersId
      // should we wrap this in critical region??
      // in theory we dont have to as the changes is convergent
      // meaning concurrency should not impact the leader promotion
      // #monotonic
      r <- maybeCandidate match {
            case Some(cand) =>
              val totalVotes = cand.receivedVotes.count {
                case (_, voted) => voted
              }
              val enoughVotes = totalVotes * 2 > peersId.size + 1
              if (enoughVotes) {
                eventLogger.elected(metadata.currentTerm, lastLog.map(_.idx)) *>
                allState.serverTpe.update {
                  case c: Candidate =>
                    val nextLogIdx = lastLog.map(_.idx).getOrElse(0) + 1
                    Leader(
                      c.commitIdx,
                      c.lastApplied,
                      peersId.map(_ -> nextLogIdx).toMap, // todo: might be wrong when no logs
                      peersId.map(_ -> 0).toMap
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
