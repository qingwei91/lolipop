package raft.debug

import cats.Applicative
import raft.algebra.event.EventsLogger
import io.circe._
import io.circe.literal._
import io.circe.syntax._
import io.circe.generic.auto._
import raft.model._

class JsonEventsLogger[F[_]: Applicative, Cmd: Encoder, State: Encoder](jsonToFile: Json => F[Unit])
    extends EventsLogger[F, Cmd, State] {

  override def receivedClientCmd(cmd: Cmd): F[Unit] = jsonToFile {
    Json.obj(
      "tpe" -> "ReceivedClientCmd".asJson,
      "cmd" -> cmd.asJson
    )
  }

  override def replyClientWriteReq(req: Cmd, res: WriteResponse): F[Unit] = jsonToFile {
    Json.obj(
      "tpe" -> "ReplyClientWrite".asJson,
      "req" -> req.asJson,
      "res" -> res.asJson
    )
  }

  override def receivedClientRead: F[Unit] = Applicative[F].unit

  override def replyClientRead(res: ReadResponse[State]): F[Unit] = Applicative[F].unit

  override def voteRPCStarted(voteRequest: VoteRequest, receiverId: String): F[Unit] = jsonToFile(
    Json.obj(
      "tpe"      -> "VoteRPCStarted".asJson,
      "req"      -> voteRequest.asJson,
      "remoteId" -> receiverId.asJson
    )
  )

  override def voteRPCReplied(voteRequest: VoteRequest, response: VoteResponse): F[Unit] = jsonToFile(
    Json.obj(
      "tpe" -> "VoteRPCReplied".asJson,
      "req" -> voteRequest.asJson,
      "res" -> response.asJson,
    )
  )

  override def voteRPCEnded(voteRequest: VoteRequest, receiverId: String, response: VoteResponse): F[Unit] = jsonToFile(
    Json.obj(
      "tpe"      -> "VoteRPCEnded".asJson,
      "remoteId" -> receiverId.asJson,
      "req"      -> voteRequest.asJson,
      "res"      -> response.asJson
    )
  )

  override def elected(term: Int, lastLog: Option[Int]): F[Unit] = jsonToFile(
    Json.obj(
      "tpe"     -> "Elected".asJson,
      "term"    -> term.asJson,
      "lastLog" -> lastLog.asJson,
    )
  )

  override def appendRPCStarted(request: AppendRequest[Cmd], receiverId: String): F[Unit] = jsonToFile(
    Json.obj(
      "tpe"      -> "AppendRPCStarted".asJson,
      "remoteId" -> receiverId.asJson,
      "req"      -> request.asJson,
    )
  )

  override def appendRPCReplied(request: AppendRequest[Cmd], response: AppendResponse): F[Unit] = jsonToFile(
    Json.obj(
      "tpe" -> "AppendRPCReplied".asJson,
      "req" -> request.asJson,
      "res" -> response.asJson
    )
  )

  override def appendRPCEnded(request: AppendRequest[Cmd], response: AppendResponse): F[Unit] = jsonToFile(
    Json.obj(
      "tpe" -> "AppendRPCEnded".asJson,
      "req" -> request.asJson,
      "res" -> response.asJson
    )
  )

  override def logCommitted(idx: Int, cmd: Cmd): F[Unit] = jsonToFile(
    Json.obj("tpe" -> "LogCommitted".asJson, "idx" -> idx.asJson, "cmd" -> cmd.asJson)
  )
  override def errorLogs(message: String): F[Unit] = Applicative[F].unit
}
