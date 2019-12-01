package raft
package proptest
package cluster

import cats.Monad
import cats.effect.concurrent.Ref
import cats.effect.{ Concurrent, Sync, Timer }

import scala.concurrent.duration._

trait ClusterApi[F[_], Req, Res] {
  def start: F[Unit]
  def sleep(duration: FiniteDuration): F[Unit]
  def stop: F[Unit]
  def read(req: Req): F[Res]
  def write(req: Req): F[Res]
}

object ClusterApi {
  @SuppressWarnings(Array("org.wartremover.warts.Any"))
  def apply[F[_]: Sync: Concurrent: Timer, Cmd, State](
    cluster: Map[String, RaftProcess[F, Cmd, State]],
    clusterState: Ref[F, ClusterState[F]]
  ): ClusterApi[F, Cmd, State] = new ClusterApi[F, Cmd, State] {
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

    override def read(req: Cmd): F[State] = {
      client.loopOverApi[F, Cmd, State](_.staleRead(req), cluster.mapValues(_.api), 200.millis)
    }
    override def write(req: Cmd): F[State] = {
      client.loopOverApi[F, Cmd, State](_.write(req), cluster.mapValues(_.api), 200.millis)
    }
  }
}

case class ClusterState[F[_]](running: Set[String], stopped: Set[String], terminators: Map[String, F[Unit]])

object ClusterState {
  def init[F[_]]: ClusterState[F] = ClusterState(Set.empty, Set.empty, Map.empty)
}
