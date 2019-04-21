package raft
package model

case class ClusterConfig(nodeId: String, peersId: Set[String])
