package raft.model

case class ClusterMembership(selfId: String, peersId: Set[String])
