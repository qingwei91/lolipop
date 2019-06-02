package raft.algebra.client

trait ClientRead[F[_], State] {
  def read: F[State]
}
