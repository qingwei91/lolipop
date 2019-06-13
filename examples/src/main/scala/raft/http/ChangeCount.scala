package raft.http

import cats.Show

case class ChangeCount(i: Int)

object ChangeCount {
  implicit val showCC: Show[ChangeCount] = Show.show[ChangeCount](_.i.toString)
}
