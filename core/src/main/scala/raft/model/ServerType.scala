package raft
package model

sealed trait ServerType {
  def commitIdx: Int
  def lastApplied: Int
}

sealed trait NonLeader extends ServerType {
  def lastRPCTimeMillis: Long
}

case class Leader(commitIdx: Int, lastApplied: Int, nextIndices: Map[String, Int], matchIndices: Map[String, Int])
    extends ServerType

case class Candidate(commitIdx: Int, lastApplied: Int, lastRPCTimeMillis: Long, receivedVotes: Map[String, Boolean])
    extends NonLeader

case class Follower(commitIdx: Int, lastApplied: Int, lastRPCTimeMillis: Long, leaderId: Option[String])
    extends NonLeader
