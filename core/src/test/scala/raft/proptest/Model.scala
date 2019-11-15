package raft.proptest

/*
  Model does not encapsulate state, this is to allow
  caller (checker specifically) to be able to
  reuse the state

  We cannot reuse StateMachine as Model under test because
  StateMachine assumes all operations are in-mem and thus
  has a much smaller api surface, eg. there's no way a read/write
  can fail or timeout
*/
trait Model[F[_], Op, Res, St] {
  def step(st: St, op: Op): F[(St, Res)]
}

