package raft
package model

import cats.Show

case class VoteResponse(term: Int, voteGranted: Boolean)

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
