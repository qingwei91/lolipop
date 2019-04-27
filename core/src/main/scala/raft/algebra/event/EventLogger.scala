package raft.algebra.event

trait EventLogger[F[_], Cmd, State] {
  def receivedClientReq(cmd: Cmd): F[Unit]
  def electionStarted(term: Int, lastLogIdx: Int): F[Unit]
  def voteGranted(term: Int, lastLogIdx: Int): F[Unit]
  def voteRejected(term: Int, targetNode: String): F[Unit]
  def elected(term: Int, lastLog: Int): F[Unit]
  def replicationStarted(term: Int): F[Unit]
  def replicationSucceeded(term: Int, latestLog: Int, follower: String): F[Unit]
  def logCommitted(idx: Int): F[Unit]
  def stateUpdated(state: State): F[Unit]
}
