package raft
package model

case class Persistent[Log](currentTerm: Int, votedFor: Option[String], logs: Vector[Log])

object Persistent {
  def init[Log] = Persistent(
    0,
    None,
    Vector.empty[Log]
  )
}
