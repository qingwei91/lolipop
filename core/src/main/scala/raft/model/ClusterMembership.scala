package raft.model

case class ClusterMembership(selfId: String, peersId: Set[String]) {
  val allNodes: Set[String] = peersId + selfId
}

object ClusterMembership {
  implicit def toOps[St](st: St)(implicit stToMembership: St => ClusterMembership): ClusterMembershipOps =
    new ClusterMembershipOps {
      override def getMembership: ClusterMembership = stToMembership(st)
    }
}

trait ClusterMembershipOps {
  def getMembership: ClusterMembership
}
