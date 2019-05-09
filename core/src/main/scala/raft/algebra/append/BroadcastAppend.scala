package raft
package algebra.append

trait BroadcastAppend[F[_]] {
  def replicateLogs: F[Map[String, F[Unit]]]
}
