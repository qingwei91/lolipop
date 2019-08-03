package raft.model

import cats.Show

case class AppendResponse(term: Int, success: Boolean)
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
