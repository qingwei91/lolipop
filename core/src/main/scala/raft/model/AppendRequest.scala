package raft.model

import cats.Show

case class AppendRequest[Cmd](
  term: Int,
  leaderId: String,
  prevLogIdx: Option[Int],
  prevLogTerm: Option[Int],
  entries: Seq[RaftLog[Cmd]],
  leaderCommit: Int
)
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
