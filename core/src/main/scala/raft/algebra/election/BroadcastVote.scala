package raft
package algebra.election

trait BroadcastVote[F[_]] {
  def requestVotes: F[Unit]
}
