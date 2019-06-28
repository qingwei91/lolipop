package raft
package model

case class Metadata(currentTerm: Int, votedFor: Option[String])

object Metadata {
  def init = Metadata(
    0,
    None
  )
}
