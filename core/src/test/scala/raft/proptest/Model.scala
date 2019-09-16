package raft.proptest

trait Model[F[_], Op, Res, St] {
  def step(st: St, op: Op): F[(St, Res)]
}
