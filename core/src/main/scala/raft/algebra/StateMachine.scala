package raft.algebra

import cats.effect.concurrent.Ref

trait StateMachine[F[_], Cmd, State] {
  def execute(cmd: Cmd): F[State]
}

object StateMachine {
  def fromRef[F[_], Cmd, St](ref: Ref[F, St])(mod: St => Cmd => St): StateMachine[F, Cmd, St] =
    new StateMachine[F, Cmd, St] {
      override def execute(cmd: Cmd): F[St] = ref.modify { st =>
        val updated = mod(st)(cmd)
        updated -> updated
      }
    }
}
