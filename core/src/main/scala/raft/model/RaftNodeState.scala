package raft
package model

import cats.Monad
import cats.effect.concurrent.{ MVar, Ref }
import raft.algebra.io.LogIO

trait RaftNodeState[F[_], Cmd] {
  def config: ClusterConfig
  def persistent: Ref[F, Persistent]
  def serverTpe: Ref[F, ServerType]
  def serverTpeLock: MVar[F, Unit]
  def logs: LogIO[F, Cmd]

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
