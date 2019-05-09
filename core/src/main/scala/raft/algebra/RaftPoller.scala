package raft
package algebra

import fs2.Stream

trait RaftPoller[F[_]] {
  def start: Stream[F, Map[String, F[Unit]]]
}
