package raft
package model

import cats.Show

case class VoteRequest(term: Int, candidateID: String, lastLogIdx: Option[Int], lastLogTerm: Option[Int])

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
object AppendResponse {
  @SuppressWarnings(Array("org.wartremover.warts.Any", "org.wartremover.warts.Null"))
  implicit val showReq: Show[AppendResponse] = new Show[AppendResponse] {
    override def show(t: AppendResponse): String =
      s"""Append Response
         |  term        = ${t.term}
         |  result      = ${if (t.success) "success" else "fail"}
         """.stripMargin
  }
}

object VoteResponse {
  @SuppressWarnings(Array("org.wartremover.warts.Any", "org.wartremover.warts.Null"))
  implicit val showReq: Show[VoteResponse] = new Show[VoteResponse] {
    override def show(t: VoteResponse): String =
      s"""Append Response
         |  term        = ${t.term}
         |  voteGranted = ${if (t.voteGranted) "yes" else "no"}
         """.stripMargin
  }
}
