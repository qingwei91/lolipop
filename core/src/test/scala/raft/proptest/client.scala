package raft
package proptest

import cats.Monad
import cats.effect.{ Concurrent, Timer }
import raft.algebra.client.{ ClientRead, ClientWrite }
import raft.model.{ ClientResponse, CommandCommitted, NoLeader, RedirectTo }

import scala.concurrent.duration.FiniteDuration

@SuppressWarnings(Array("org.wartremover.warts.All"))
object client {
  type Client[F[_], Cmd, Res] = ClientWrite[F, Cmd, Res] with ClientRead[F, Cmd, Res]
  def loopOverApi[F[_]: Monad, Cmd, Res](
    fireRequest: Client[F, Cmd, Res] => F[ClientResponse[Res]],
    cluster: Map[String, Client[F, Cmd, Res]],
    sleepTime: FiniteDuration
  )(implicit t: Timer[F], con: Concurrent[F]): F[Res] = {
    def innerLoop(selected: (String, Client[F, Cmd, Res])): F[Res] = {
      fireRequest(selected._2).flatMap {
        case RedirectTo(nodeID) => innerLoop(nodeID -> cluster(nodeID))

        case NoLeader =>
          // Not robust as it does not try every single api
          // todo: Fix it so that it retry with something else
          val randomSelected = cluster.filterKeys(_ != selected._1).head
          t.sleep(sleepTime) *> innerLoop(randomSelected)

        case CommandCommitted(res) => res.pure[F]
      }
    }
    innerLoop(cluster.head)
  }

}
