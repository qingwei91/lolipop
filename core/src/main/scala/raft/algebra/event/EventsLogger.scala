package raft.algebra.event

import raft.model._

trait EventsLogger[F[_], Cmd, State] {
  def receivedClientCmd(cmd: Cmd): F[Unit]
  def replyClientWriteReq(req: Cmd, res: WriteResponse): F[Unit]

  def receivedClientRead: F[Unit]
  def replyClientRead(res: ReadResponse[State]): F[Unit]

  def voteRPCStarted(voteRequest: VoteRequest, receiverId: String): F[Unit]
  def voteRPCReplied(voteRequest: VoteRequest, response: VoteResponse): F[Unit]
  def voteRPCEnded(voteRequest: VoteRequest, receiverId: String, response: VoteResponse): F[Unit]

  def elected(term: Int, lastLog: Option[Int]): F[Unit]

  def appendRPCStarted(request: AppendRequest[Cmd], receiverId: String): F[Unit]
  def appendRPCReplied(request: AppendRequest[Cmd], response: AppendResponse): F[Unit]
  def appendRPCEnded(request: AppendRequest[Cmd], response: AppendResponse): F[Unit]

  def logCommittedAndExecuted(idx: Int, cmd: Cmd, latest: State): F[Unit]

  def errorLogs(message: String): F[Unit]
  def processTerminated: F[Unit]
}
