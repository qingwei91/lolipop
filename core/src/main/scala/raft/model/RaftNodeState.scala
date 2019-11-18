package raft
package model

import cats.Monad
import cats.effect.{ Concurrent, Timer }
import cats.effect.concurrent.{ MVar, Ref }
import raft.algebra.io.{ LogsApi, MetadataIO }

import scala.concurrent.duration.MILLISECONDS

trait RaftNodeState[F[_], Cmd] {
  def config: ClusterConfig
  def metadata: MetadataIO[F]
  def serverTpe: Ref[F, ServerType]
  def serverTpeLock: MVar[F, Unit]
  def logs: LogsApi[F, Cmd]

  // TODO: this is buggy, we should use bracket
  // TODO: this is error prone, think about avoding MVar?
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

object RaftNodeState {
  def init[F[_]: Timer: Monad: Concurrent, Cmd](
    clusterConfig: ClusterConfig,
    metaIO: MetadataIO[F],
    logIO: LogsApi[F, Cmd]
  ): F[RaftNodeState[F, Cmd]] = {
    for {
      time <- Timer[F].clock.realTime(MILLISECONDS)
      initFollower = Follower(0, 0, time, None)
      serverTpeRef <- Ref.of[F, ServerType](initFollower)
      lock         <- MVar[F].of(())
    } yield {
      new RaftNodeState[F, Cmd] {
        override def config: ClusterConfig = clusterConfig

        override def metadata: MetadataIO[F] = metaIO

        override def serverTpe: Ref[F, ServerType] = serverTpeRef

        override def serverTpeLock: MVar[F, Unit] = lock

        override def logs: LogsApi[F, Cmd] = logIO
      }
    }
  }
}
