package raft.setup

import cats.Monad
import cats.effect.Timer
import cats.implicits._
import raft.algebra.client.ClientWrite
import raft.model._

import scala.concurrent.duration._

object TestClient {
  def untilCommitted[F[_]: Monad: Timer, Cmd](
    clients: Map[String, ClientWrite[F, Cmd]]
  )(nodeId: String, cmd: Cmd): F[ClientResponse] = {
    clients(nodeId).write(cmd).flatMap {
      case RedirectTo(leaderId) =>
        Timer[F].sleep(300.millis) *> untilCommitted(clients)(leaderId, cmd)
      case CommandCommitted => Monad[F].pure(CommandCommitted)
      case NoLeader =>
        val total   = clients.size
        val keyList = clients.keySet.toList
        val currIdx = keyList.indexOf(nodeId)
        val nextIdx = (currIdx + 1) % total
        val nextId  = keyList(nextIdx)

        Timer[F].sleep(300.millis) *> untilCommitted(clients)(nextId, cmd)
    }
  }
}
