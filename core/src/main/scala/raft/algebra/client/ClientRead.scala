package raft.algebra.client

import raft.model.ReadResponse

trait ClientRead[F[_], State] {
  def read: F[ReadResponse[State]]
}
