package raft
package algebra

trait StateMachine[F[_], Cmd, State] {
  def execute(cmd: Cmd): F[State]
  def getCurrent: F[State]
}
