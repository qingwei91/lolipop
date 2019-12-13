package raft
package proptest
package cluster
import cats.effect.concurrent.Ref
import raft.algebra.io.NetworkIO
import raft.model.{ AppendRequest, AppendResponse }
import raft.model.{ VoteRequest, VoteResponse }
import cats.Monad
import cats.MonadError
import cats.effect.Sync

class ProxiedNetwork[F[_]: Sync, Cmd](clusterState: Ref[F, ClusterState[F]], actual: NetworkIO[F, Cmd])
    extends NetworkIO[F, Cmd] {
  override def sendAppendRequest(nodeID: String, appendReq: AppendRequest[Cmd]): F[AppendResponse] = {
    for {
      state <- clusterState.get
      res <- if (state.running.contains(nodeID)) {
              actual.sendAppendRequest(nodeID, appendReq)
            } else {
              MonadError[F, Throwable].raiseError(
                new RuntimeException(s"Network partition, node $nodeID is not reachable")
              )
            }
    } yield {
      res
    }
  }
  override def sendVoteRequest(nodeID: String, voteRq: VoteRequest): F[VoteResponse] = {
    for {
      state <- clusterState.get
      res <- if (state.running.contains(nodeID)) {
              actual.sendVoteRequest(nodeID, voteRq)
            } else {
              MonadError[F, Throwable].raiseError(
                new RuntimeException(s"Network partition, node $nodeID is not reachable")
              )
            }
    } yield {
      res
    }
  }
}
