package raft.algebra.client

import raft.model.ReadResponse

trait ClientRead[F[_], Cmd, State] {
  def read(readCmd: Cmd): F[ReadResponse[State]]
}
