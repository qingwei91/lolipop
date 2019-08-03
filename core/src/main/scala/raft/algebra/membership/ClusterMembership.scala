package raft.algebra.membership

case class ClusterMembership(selfId: String, peersId: Set[String])
