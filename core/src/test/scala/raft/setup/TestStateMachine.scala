package raft.setup

import cats._
import cats.effect.Sync
import cats.effect.concurrent.Ref
import cats.implicits._
import raft.algebra.StateMachine

class TestStateMachine[F[_]: Monad: Sync] extends StateMachine[F, String, String] {
  val state = Ref.of[F, String]("")
  override def execute(cmd: String): F[String] = {
    for {
      st <- state
      updated <- st.modify { s =>
        val newSt = s + cmd
        newSt -> newSt
      }
    } yield updated
  }
}

object TestStateMachine {
  def init[F[_]: Monad: Sync]: F[Ref[F, TestStateMachine[F]]] = Ref.of[F, TestStateMachine[F]](new TestStateMachine[F])
}
