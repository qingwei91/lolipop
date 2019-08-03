package raft.algebra

import cats.Applicative
import cats.implicits._

trait StateMachine[F[_], Cmd, State] extends ChangeState[F, Cmd, State] with QueryState[F, State]

object StateMachine {
  def compose[F[_]: Applicative, Cmd1, Cmd2, State1, State2](
    a: StateMachine[F, Cmd1, State1],
    b: StateMachine[F, Cmd2, State2]
  ): StateMachine[F, Either[Cmd1, Cmd2], (State1, State2)] = new StateMachine[F, Either[Cmd1, Cmd2], (State1, State2)] {
    override def getCurrent: F[(State1, State2)] = (a.getCurrent, b.getCurrent).tupled

    override def execute(cmd: Either[Cmd1, Cmd2]): F[(State1, State2)] = {
      cmd match {
        case Left(c1) => (a.execute(c1), b.getCurrent).tupled
        case Right(c2) => (a.getCurrent, b.execute(c2)).tupled
      }
    }
  }
}
