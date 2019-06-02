package raft.algebra

trait StateMachine[F[_], Cmd, State] extends ChangeState[F, Cmd, State] with QueryState[F, State]
