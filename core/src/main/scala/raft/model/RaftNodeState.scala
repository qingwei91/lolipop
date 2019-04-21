package raft.model

import cats.Monad
import cats.implicits._
import cats.effect.concurrent.{ MVar, Ref }

trait RaftNodeState[F[_], Cmd] {
  def config: ClusterConfig
  def persistent: Ref[F, Persistent[RaftLog[Cmd]]]
  def serverTpe: Ref[F, ServerType]
  def serverTpeLock: MVar[F, Unit]

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
