package raft
package model

case class ClusterConfig(nodeId: String, peersId: Set[String]) {
  require(!peersId.contains(nodeId), "nodeId should not be in peersId")
}
