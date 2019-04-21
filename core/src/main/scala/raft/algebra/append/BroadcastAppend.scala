package raft
package algebra.append

trait BroadcastAppend[F[_]] {
  def replicateLogs: F[Unit]
}
