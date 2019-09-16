package raft
package proptest
package cluster

import cats.effect.concurrent.Ref
import cats.effect.{ Concurrent, Sync, Timer }
import cats.Monad

import scala.concurrent.duration.FiniteDuration

sealed trait ClusterOps
case object StartAll extends ClusterOps
case class Sleep(duration: FiniteDuration) extends ClusterOps
case object StopAll extends ClusterOps

@SuppressWarnings(Array("org.wartremover.warts.Any"))
object ClusterOps {
  // take a Map[String, RaftProcess], produce a Cluster control plane
  // client

  case class ClusterState[F[_]](running: Set[String], stopped: Set[String], terminators: Map[String, F[Unit]])

  // interpret clusterOps into F[_]
  def execute[F[_]: Sync: Concurrent: Timer, Cmd, State](
    cluster: Map[String, RaftProcess[F, Cmd, State]],
    clusterState: Ref[F, ClusterState[F]]
  )(fa: ClusterOps): F[Unit] = {
    fa match {
      case StartAll =>
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
      case StopAll =>
        cluster.toList.traverse_ {
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
      case Sleep(duration) => Timer[F].sleep(duration)
    }

  }
}
