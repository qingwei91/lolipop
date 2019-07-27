package raft.algebra.io

import raft.model.{ AppendRequest, AppendResponse, VoteRequest, VoteResponse }

trait NetworkIO[F[_], Cmd] {
  def sendAppendRequest(nodeID: String, appendReq: AppendRequest[Cmd]): F[AppendResponse]
  def sendVoteRequest(nodeID: String, voteRq: VoteRequest): F[VoteResponse]
}
