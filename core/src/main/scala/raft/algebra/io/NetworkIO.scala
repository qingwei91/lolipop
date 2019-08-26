package raft.algebra.io

import raft.model._

/**
  * The implementation of this trait needs to cover selfId, eg. if Leader
  * is calling sendAppendRequest, it will send an AppendRequest to itself
  * too
  *
  * This is because I wish to centralize the logic that determine
  * StateMachine, which is currently driven by AppendResponse, a log is
  * only consider committed if it is replicated by more than half of the
  * nodes, and if the cluster only contains 1 node, then we either need to
  * handle 1 node as a special case, or we can send AppendRequest to that 1
  * node (which is self), then it can be handled in the same way
  *
  * I've chosen to not write a special case for it, to be revisit once
  * everything is put in place
  */
trait NetworkIO[F[_], Cmd] {
  def sendAppendRequest(nodeID: String, appendReq: AppendRequest[Cmd]): F[AppendResponse]
  def sendVoteRequest(nodeID: String, voteRq: VoteRequest): F[VoteResponse]
}
