package raft.setup

import java.time.LocalTime

import cats.syntax.show._
import cats.effect.concurrent.Ref
import cats.{Applicative, Show}
import raft.algebra.event.EventsLogger
import raft.model._

@SuppressWarnings(Array("org.wartremover.warts.Any", "org.wartremover.warts.Null"))
class InMemEventsLogger[F[_]: Applicative, Cmd: Show, State: Show](val nodeId: String, val logs: Ref[F, StringBuffer])
    extends EventsLogger[F, Cmd, State] {

  private def add(s: String): F[Unit] = {
    logs.update(_.append(s"\n${LocalTime.now()} Node-$nodeId: $s"))
  }

  override def receivedClientCmd(cmd: Cmd): F[Unit] = {
    add(s"Received ${cmd.show} from client")
  }
  override def replyClientWriteReq(req: Cmd, res: WriteResponse[State]): F[Unit] = {
    add(s"Reply $res to client for $req")
  }

  override def receivedClientRead: F[Unit]                        = add(s"Client tries to read state")
  override def replyClientRead(res: ReadResponse[State]): F[Unit] = add(s"Return $res to client for read")

  def voteRPCStarted(voteRequest: VoteRequest, receiverId: String): F[Unit] =
    add(s"Candidate start vote rpc to $receiverId with ${voteRequest.show}")

  override def voteRPCEnded(voteRequest: VoteRequest, peerId: String, response: VoteResponse): F[Unit] =
    add(s"Candidate received vote for ${voteRequest.show} from $peerId")

  override def voteRPCReplied(voteRequest: VoteRequest, response: VoteResponse): F[Unit] =
    add(s"Reply vote for ${voteRequest.show} with ${response.show}")

  override def elected(term: Int, lastLog: Option[Int]): F[Unit] =
    add(s"Elected as leader of $term, lastLog=${lastLog.orNull}")

  override def appendRPCStarted(request: AppendRequest[Cmd], receiverId: String): F[Unit] =
    add(s"Leader start append rpc to $receiverId with ${request.show}")
  override def appendRPCReplied(request: AppendRequest[Cmd], response: AppendResponse): F[Unit] =
    add(s"Reply append rpc to ${request.show} with ${response.show}")
  override def appendRPCEnded(request: AppendRequest[Cmd], response: AppendResponse): F[Unit] =
    add(s"Leader received append rpc response ${response.show} for ${request.show}")

  override def logCommittedAndExecuted(idx: Int, cmd: Cmd, latest: State): F[Unit] = add(s"Committed idx=$idx, cmd=$cmd, state=$latest")

  override def errorLogs(message: String): F[Unit] = add(message)
  override def processTerminated: F[Unit] = add("RaftProcess terminated")
}
