package raft.setup

import java.io.IOException

import cats.MonadError
import raft.algebra.io.NetworkIO
import raft.model._

class UnreliableNetwork[F[_]](inner: NetworkIO[F, String], shouldFail: (String, String) => Boolean)(
  implicit monadError: MonadError[F, Throwable]
) extends NetworkIO[F, String] {

  override def sendAppendRequest(nodeID: String, appendReq: AppendRequest[String]): F[AppendResponse] = {
    if (shouldFail(nodeID, appendReq.leaderId)) {
      monadError.raiseError(new IOException(s"Network failed of $nodeID"))
    } else {
      inner.sendAppendRequest(nodeID, appendReq)
    }

  }

  override def sendVoteRequest(nodeID: String, voteRq: VoteRequest): F[VoteResponse] = {
    if (shouldFail(nodeID, voteRq.candidateID)) {
      monadError.raiseError(new IOException(s"Network failed of $nodeID"))
    } else {
      inner.sendVoteRequest(nodeID, voteRq)
    }
  }
}
