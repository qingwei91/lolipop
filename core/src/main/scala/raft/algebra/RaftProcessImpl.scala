package raft
package algebra

import cats.Monad
import cats.effect.{ Concurrent, Fiber }
import fs2.Stream
import fs2.concurrent.Queue

class RaftProcessImpl[F[_]: Monad: Concurrent](
  poller: RaftPoller[F],
  replicationTaskQueue: Queue[F, F[Unit]]
) extends RaftProcess[F] {
  type Ongoing = Fiber[F, Unit]
  override def startRaft: Stream[F, Unit] = {
    val replicationTask: Stream[F, Unit] = replicationTaskQueue.dequeue.evalMap[F, Unit](identity)

    poller.start.concurrently[F, Unit](replicationTask)
  }
}
