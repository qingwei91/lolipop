package raft.model

import cats.Eq

case class AddMemberRequest(newMemberId: String)

object AddMemberRequest {
  implicit val eq: Eq[AddMemberRequest] = Eq.fromUniversalEquals[AddMemberRequest]
}
