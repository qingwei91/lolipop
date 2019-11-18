package raft.algebra.client

import raft.model.ClientResponse

trait ClientRead[F[_], Cmd, Res] {
  // todo: Implement linearizable read
  def staleRead(readCmd: Cmd): F[ClientResponse[Res]]
}
