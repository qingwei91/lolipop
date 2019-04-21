package raft.algebra.event

trait EventLogger[F[_]] {
  def log(ev: RaftEvent, message: String): F[Unit]
}
