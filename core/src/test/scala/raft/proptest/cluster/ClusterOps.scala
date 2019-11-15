package raft
package proptest
package cluster

import cats.effect.concurrent.Ref
import cats.effect.{ Concurrent, Sync, Timer }
import cats.Monad

import scala.concurrent.duration.FiniteDuration

trait ClusterManager[F[_]] {
  def start: F[Unit]
  def sleep(duration: FiniteDuration): F[Unit]
  def stop: F[Unit]
}

object ClusterManager {
  @SuppressWarnings(Array("org.wartremover.warts.Any"))
  def apply[F[_]: Sync: Concurrent: Timer, Cmd, State](
    cluster: Map[String, RaftProcess[F, Cmd, State]],
    clusterState: Ref[F, ClusterState[F]]
  ): ClusterManager[F] = new ClusterManager[F] {
    override def start: F[Unit] = {
      cluster.toList.traverse_ {
        case (id, proc) =>
          for {
            state <- clusterState.get
            _ <- if (state.running.contains(id)) {
                  Monad[F].unit
                } else {
                  for {
                    allocated <- proc.startRaft.allocated
                    (procStream, term) = allocated
                    _ <- Concurrent[F].start(procStream.compile.drain)
                    _ <- clusterState.update { st =>
                          st.copy(running = st.running + id, terminators = st.terminators.updated(id, term))
                        }
                  } yield ()
                }
          } yield {}
      }
    }

    override def sleep(duration: FiniteDuration): F[Unit] = Timer[F].sleep(duration)

    override def stop: F[Unit] = cluster.toList.traverse_ {
      case (id, _) =>
        for {
          state <- clusterState.get
          _ <- if (!state.running.contains(id)) {
                Monad[F].unit
              } else {
                for {
                  terminators <- clusterState.get.map(_.terminators)
                  _ <- terminators.get(id) match {
                        case None => Monad[F].unit
                        case Some(t) => t
                      }

                  _ <- clusterState.update { st =>
                        st.copy(running = st.running - id, terminators = st.terminators - id)
                      }
                } yield ()
              }
        } yield ()
    }
  }
}

case class ClusterState[F[_]](running: Set[String], stopped: Set[String], terminators: Map[String, F[Unit]])

