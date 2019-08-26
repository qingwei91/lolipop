package raft.algebra.election

import raft.model.{ VoteRequest, VoteResponse }

trait SingleVoteHandler[F[_]] {
  def processVote(votedBy: String, res: VoteResponse, req: VoteRequest): F[Unit]
}
