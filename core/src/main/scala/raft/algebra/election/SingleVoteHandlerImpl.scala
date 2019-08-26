package raft
package algebra
package election

import raft.algebra.event.EventsLogger
import raft.model._
import ClusterMembership._
import cats.Monad

class SingleVoteHandlerImpl[F[_], Cmd, State](
  allState: RaftNodeState[F, Cmd],
  eventLogger: EventsLogger[F, Cmd, State],
  queryState: QueryState[F, State]
)(implicit F: Monad[F], stateToMembership: State => ClusterMembership)
    extends SingleVoteHandler[F] {
  override def processVote(votedBy: String, res: VoteResponse, req: VoteRequest): F[Unit] = allState.serverTpeMutex {
    for {

      maybeCandidate <- allState.serverTpe.modify {
                         case c: Candidate =>
                           val nState = c.copy(receivedVotes = c.receivedVotes.updated(votedBy, res.voteGranted))
                           nState -> Some(nState)
                         case other => other -> None
                       }
      _        <- eventLogger.voteRPCEnded(req, votedBy, res)
      metadata <- allState.metadata.get
      lastLog  <- allState.logs.lastLog

      currentCluster <- queryState.getCurrent.map(_.getMembership)
      allNodesId = currentCluster.allNodes
      // should we wrap this in critical region??
      // in theory we dont have to as the changes is convergent
      // meaning concurrency should not impact the leader promotion
      // #monotonic
      r <- maybeCandidate match {
            case Some(cand) =>
              val totalVotes = cand.receivedVotes.count {
                case (_, voted) => voted
              }
              val enoughVotes = totalVotes * 2 > allNodesId.size
              if (enoughVotes) {
                eventLogger.elected(metadata.currentTerm, lastLog.map(_.idx)) *>
                allState.serverTpe.update {
                  case c: Candidate =>
                    val nextLogIdx = lastLog.map(_.idx).getOrElse(0) + 1
                    Leader(
                      c.commitIdx,
                      c.lastApplied,
                      allNodesId.map(_ -> nextLogIdx).toMap, // todo: might be wrong when no logs
                      allNodesId.map(_ -> 0).toMap
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
