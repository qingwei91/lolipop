package raft
package algebra.election

import raft.model.{ VoteRequest, VoteResponse }

trait VoteRPCHandler[F[_]] {
  def requestVote(req: VoteRequest): F[VoteResponse]
}
