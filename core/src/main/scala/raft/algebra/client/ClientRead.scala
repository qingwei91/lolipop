package raft.algebra.client

import raft.model.ReadResponse

trait ClientRead[F[_], Cmd, Res] {
  def read(readCmd: Cmd): F[ReadResponse[Res]]
}
