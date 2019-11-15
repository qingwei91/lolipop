package raft.algebra

import cats.effect.concurrent.Ref

/**
  * This trait models the **API** of a StateMachine
  * It is important that it only exposes an API and NOT necessarily
  * the internal state
  * It is up to the implementor to decide whether to expose internal state
  * as part of the API or not
  *
  * For example, you could have a Key-Value store state machine, which has
  * Map[K,V] as the internal state
  * Then you can return the latest Map[K, V] as response, or you can
  * return something else depending on the the Cmd
  *
  */
trait StateMachine[F[_], Cmd, Res] {
  def execute(cmd: Cmd): F[Res]
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
