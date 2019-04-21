package raft
package model

case class VoteRequest(term: Int, candidateID: String, lastLogIdx: Option[Int], lastLogTerm: Option[Int])
case class AppendRequest[Log](
  term: Int,
  leaderId: String,
  prevLogIdx: Option[Int],
  prevLogTerm: Option[Int],
  entries: Seq[Log],
  leaderCommit: Int
)
case class AppendResponse(term: Int, success: Boolean)
case class VoteResponse(term: Int, voteGranted: Boolean)
