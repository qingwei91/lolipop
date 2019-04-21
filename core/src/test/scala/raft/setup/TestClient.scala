package raft.setup

import cats.Monad
import cats.implicits._
import raft.algebra.client.ClientIncoming
import raft.model._

object TestClient {
  def untilCommitted[F[_]: Monad, Cmd](
    clients: Map[String, ClientIncoming[F, Cmd]]
  )(nodeId: String, cmd: Cmd): F[ClientResponse] = {
    clients(nodeId).incoming(cmd).flatMap {
      case RedirectTo(leaderId) => untilCommitted(clients)(leaderId, cmd)
      case CommandCommitted => Monad[F].pure(CommandCommitted)
      case NoLeader =>
        val total   = clients.size
        val keyList = clients.keySet.toList
        val currIdx = keyList.indexOf(nodeId)
        val nextIdx = (currIdx + 1) % total
        val nextId  = keyList(nextIdx)
        untilCommitted(clients)(nextId, cmd)
    }
  }
}
