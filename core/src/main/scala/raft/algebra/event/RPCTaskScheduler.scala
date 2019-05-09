package raft
package algebra.event

import cats.Monad
import cats.effect.Concurrent
import fs2.concurrent.Queue

/**
  * Ability to register a RPC task so that it will be executed later
  * without blocking the caller
  */
trait RPCTaskScheduler[F[_]] {
  def register(nodeId: String, task: F[Unit]): F[Unit]
}

object RPCTaskScheduler {
  def apply[F[_]: Monad: Concurrent](tasksQueue: Map[String, Queue[F, F[Unit]]]): RPCTaskScheduler[F] = {
    new RPCTaskScheduler[F] {
      override def register(nodeId: String, task: F[Unit]): F[Unit] = {
        tasksQueue
          .get(nodeId)
          .map {
            _.enqueue1(task)
          }
          .getOrElse(Monad[F].unit)
      }
    }
  }

}
