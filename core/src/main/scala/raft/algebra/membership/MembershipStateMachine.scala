package raft
package algebra
package membership

import cats.effect.concurrent.Ref
import raft.model.{ AddMemberRequest, ClusterMembership }

class MembershipStateMachine[F[_]](ref: Ref[F, ClusterMembership])
    extends StateMachine[F, AddMemberRequest, ClusterMembership] {
  override def getCurrent: F[ClusterMembership] = ref.get

  override def execute(cmd: AddMemberRequest): F[ClusterMembership] = ref.modify { m =>
    val updated = m.copy(peersId = m.peersId + cmd.newMemberId)
    updated -> updated
  }
}
