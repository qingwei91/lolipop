package raft.algebra

trait QueryState[F[_], State] {
  def getCurrent: F[State]
}
