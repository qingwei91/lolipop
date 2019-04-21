package raft
package algebra

import raft.model.{ AppendRequest, AppendResponse, VoteRequest, VoteResponse }

trait NetworkIO[F[_], Log] {
  def sendAppendRequest(nodeID: String, appendReq: AppendRequest[Log]): F[AppendResponse]
  def sendVoteRequest(nodeID: String, voteRq: VoteRequest): F[VoteResponse]
}
