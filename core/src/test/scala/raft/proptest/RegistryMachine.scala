package raft.proptest

import cats.effect.concurrent.Ref
import raft.algebra.StateMachine

object RegistryMachine {
  def apply[F[_]](ref: Ref[F, Map[String, String]]): StateMachine[F, RegistryCmd, Map[String, String]] =
    StateMachine.fromRef[F, RegistryCmd, Map[String, String]](ref) { oldSt =>
      {
        case Put(k, v) => oldSt.updated(k, v)
      }
    }
}
