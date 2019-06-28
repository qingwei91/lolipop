package raft
package model

import cats.Monad
import cats.effect.concurrent.{ MVar, Ref }
import raft.algebra.io.{ LogsApi, MetadataIO }

trait RaftNodeState[F[_], Cmd] {
  def config: ClusterConfig
  def persistent: MetadataIO[F]
  def serverTpe: Ref[F, ServerType]
  def serverTpeLock: MVar[F, Unit]
  def logs: LogsApi[F, Cmd]

  def serverTpeMutex[A](fa: F[A])(implicit F: Monad[F]): F[A] = {
    for {
      _ <- serverTpeLock.take
      a <- fa
      _ <- serverTpeLock.put(())
    } yield {
      a
    }
  }
}
