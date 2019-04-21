package raft.model

case class ClusterConfig(nodeId: String, peersId: Set[String])
