package raft
package algebra.election

trait BroadcastVote[F[_]] {
  def requestVotes: F[Map[String, F[Unit]]]
}
