package raft
package algebra
package election

import cats.effect._
import raft.algebra.event.EventsLogger
import raft.algebra.io.NetworkIO
import raft.model._

class BroadcastVoteImpl[F[_]: Timer: ContextShift: Concurrent, FF[_], Cmd, State](
  allState: RaftNodeState[F, Cmd],
  networkManager: NetworkIO[F, Cmd],
  eventLogger: EventsLogger[F, Cmd, State],
  queryState: QueryState[F, State],
  voteResponseHandler: SingleVoteHandler[F]
)(implicit stateToMembership: State => ClusterMembership)
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
      _   <- voteResponseHandler.processVote(targetPeer, res, request)
    } yield ()

  }
}
