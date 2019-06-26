package raft
package model

case class Persistent(currentTerm: Int, votedFor: Option[String])

object Persistent {
  def init = Persistent(
    0,
    None
  )
}
