package raft.setup

import cats.Monad
import cats.effect.Timer
import cats.implicits._
import raft.algebra.client.{ ClientRead, ClientWrite }
import raft.model._

import scala.concurrent.duration._

object TestClient {
  def writeToLeader[F[_]: Monad: Timer, Cmd, Res](
    clients: Map[String, ClientWrite[F, Cmd, Res]]
  )(nodeId: String, cmd: Cmd): F[WriteResponse[Res]] = {
    clients(nodeId).write(cmd).flatMap {
      case RedirectTo(leaderId) =>
        Timer[F].sleep(300.millis) *> writeToLeader(clients)(leaderId, cmd)
      case committed @ CommandCommitted(_) => Monad[F].pure(committed)
      case NoLeader =>
        val total   = clients.size
        val keyList = clients.keySet.toList
        val currIdx = keyList.indexOf(nodeId)
        val nextIdx = (currIdx + 1) % total
        val nextId  = keyList(nextIdx)

        Timer[F].sleep(300.millis) *> writeToLeader(clients)(nextId, cmd)
    }
  }

  def readFromLeader[F[_]: Monad: Timer, State, Cmd](
    clients: Map[String, ClientRead[F, Cmd, State]]
  )(nodeId: String, cmd: Cmd): F[ReadResponse[State]] = {
    clients(nodeId).read(cmd).flatMap {
      case RedirectTo(leaderId) =>
        Timer[F].sleep(300.millis) *> readFromLeader(clients)(leaderId, cmd)
      case r @ Query(_) => Monad[F].pure(r)
      case NoLeader =>
        val total   = clients.size
        val keyList = clients.keySet.toList
        val currIdx = keyList.indexOf(nodeId)
        val nextIdx = (currIdx + 1) % total
        val nextId  = keyList(nextIdx)

        Timer[F].sleep(300.millis) *> readFromLeader(clients)(nextId, cmd)
    }
  }

}
