package raft
package model

import cats.Show

case class VoteRequest(term: Int, candidateID: String, lastLogIdx: Option[Int], lastLogTerm: Option[Int])

object VoteRequest {
  @SuppressWarnings(Array("org.wartremover.warts.Any", "org.wartremover.warts.Null"))
  implicit val showReq: Show[VoteRequest] = new Show[VoteRequest] {
    override def show(t: VoteRequest): String =
      s"""Vote Request
         |  term        = ${t.term}
         |  candidate   = ${t.candidateID}
         |  lastLogIdx  = ${t.lastLogIdx.orNull}
         |  lastLogTerm = ${t.lastLogTerm.orNull}""".stripMargin
  }
}

case class AppendRequest[Cmd](
  term: Int,
  leaderId: String,
  prevLogIdx: Option[Int],
  prevLogTerm: Option[Int],
  entries: Seq[RaftLog[Cmd]],
  leaderCommit: Int
)

case class AppendResponse(term: Int, success: Boolean)
case class VoteResponse(term: Int, voteGranted: Boolean)

object AppendRequest {
  @SuppressWarnings(Array("org.wartremover.warts.Any", "org.wartremover.warts.Null"))
  implicit def showReq[Cmd: Show]: Show[AppendRequest[Cmd]] = {
    Show.show[AppendRequest[Cmd]] { req =>
      import req._
      s"""
         |AppendReq
         |  term          = $term
         |  leaderId      = $leaderId
         |  prevLogIdx    = ${prevLogIdx.orNull}
         |  prevLogTerm   = ${prevLogTerm.orNull}
         |  entries       = ${entries.map(_.show).mkString("\n")}
         |  leaderCommit  = $leaderCommit""".stripMargin
    }
  }
}
