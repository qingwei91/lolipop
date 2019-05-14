package raft.http

import cats.effect.Sync
import io.circe.Encoder
import io.circe.generic.auto._
import org.http4s.Method._
import org.http4s._
import org.http4s.circe._
import org.http4s.client._
import org.http4s.client.dsl.Http4sClientDsl
import raft.algebra.io.NetworkIO
import raft.model._

class HttpNetwork[F[_]: Sync, Cmd: Encoder](networkConfig: Map[String, Uri], client: Client[F])
    extends NetworkIO[F, Cmd]
    with Http4sClientDsl[F]
    with CirceEntityEncoder
    with CirceEntityDecoder {
  override def sendAppendRequest(nodeID: String, appendReq: AppendRequest[Cmd]): F[AppendResponse] = {

    val uri = networkConfig(nodeID) / "append"

    client.expect[AppendResponse](POST.apply(body = appendReq, uri = uri))
  }

  override def sendVoteRequest(nodeID: String, voteRq: VoteRequest): F[VoteResponse] = {
    val uri = networkConfig(nodeID) / "vote"
    client.expect[VoteResponse](POST.apply(body = voteRq, uri = uri))
  }
}
