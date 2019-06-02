package raft
package algebra

trait ChangeState[F[_], Cmd, State] {
  def execute(cmd: Cmd): F[State]
}
