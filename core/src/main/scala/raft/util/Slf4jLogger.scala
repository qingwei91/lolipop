package raft
package util

import cats.effect.Sync
import org.slf4j.LoggerFactory
import raft.algebra.event.EventsLogger
import raft.model._

class Slf4jLogger[F[_]: Sync, Cmd, State](nodeId: String) extends EventsLogger[F, Cmd, State] {
  private val logger = LoggerFactory.getLogger(s"${this.getClass.getSimpleName}-$nodeId")
  private val F      = Sync[F]

  override def receivedClientCmd(cmd: Cmd): F[Unit] = F.delay(logger.info(s"Received command $cmd from client"))

  override def replyClientWriteReq(req: Cmd, res: WriteResponse): F[Unit] =
    F.delay(logger.info(s"Replied client, incoming cmd = $req, response = $res"))

  override def receivedClientRead: F[Unit] = F.delay(logger.debug("Received client read request"))

  override def replyClientRead(res: ReadResponse[State]): F[Unit] =
    F.delay(logger.debug(s"Replied read request with $res"))

  override def voteRPCStarted(voteRequest: VoteRequest, receiverId: String): F[Unit] =
    F.delay(logger.info(s"Vote RPC started, sent $voteRequest to $receiverId"))

  override def voteRPCReplied(voteRequest: VoteRequest, response: VoteResponse): F[Unit] =
    F.delay(logger.info(s"Replied voteRequest $voteRequest with $response"))

  override def voteRPCEnded(voteRequest: VoteRequest, receiverId: String, response: VoteResponse): F[Unit] =
    F.delay(logger.info(s"VoteRPC ended by $voteRequest to $receiverId and got back $response"))

  override def elected(term: Int, lastLog: Option[Int]): F[Unit] =
    F.delay(logger.info(s"Elected as leader of $term, lastLog = $lastLog"))

  override def appendRPCStarted(request: AppendRequest[Cmd], receiverId: String): F[Unit] =
    F.delay(logger.info(s"Start append RPC, $request to $receiverId"))

  override def appendRPCReplied(request: AppendRequest[Cmd], response: AppendResponse): F[Unit] =
    F.delay(logger.info(s"Replied appendRPC to $request wtih $response"))

  override def appendRPCEnded(request: AppendRequest[Cmd], response: AppendResponse): F[Unit] =
    F.delay(logger.info(s"Append RPC ended by $request by $response"))

  override def logCommittedAndExecuted(idx: Int, cmd: Cmd, latest: State): F[Unit] =
    F.delay(logger.info(s"Log committed at $idx with $cmd, lastest state = $latest"))

  override def errorLogs(message: String): F[Unit] = F.delay(logger.error(message))

  override def processTerminated: F[Unit] = F.delay(logger.warn("Tada, terminating...."))
}
