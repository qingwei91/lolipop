package raft
package algebra

import fs2.Stream

trait RaftProcess[F[_]] {
  def startRaft: Stream[F, Unit]
}
