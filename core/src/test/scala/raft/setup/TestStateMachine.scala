package raft.setup

import cats._
import cats.effect.Sync
import cats.effect.concurrent.Ref
import cats.implicits._
import raft.algebra.StateMachine

class TestStateMachine[F[_]: Monad: Sync](state: Ref[F, String]) extends StateMachine[F, String, String] {
  override def execute(cmd: String): F[String] = {
    for {
      updated <- state.modify { s =>
                  val newSt = s + cmd
                  newSt -> newSt
                }
    } yield updated
  }

  override def getCurrent: F[String] = state.get
}
