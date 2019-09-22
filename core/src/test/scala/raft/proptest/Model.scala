package raft.proptest

// Model does not encapsulate state, this is to allow
// caller (checker specifically) to be able to
// reuse the state
trait Model[F[_], Op, Res, St] {
  def step(st: St, op: Op): F[(St, Res)]
}
