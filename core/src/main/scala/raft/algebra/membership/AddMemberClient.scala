package raft.algebra.membership

trait AddMemberClient[F[_]] {
  def addMember(nodeToAdd: String, targetNode: String): F[Unit]
}
