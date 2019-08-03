package raft.model

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
