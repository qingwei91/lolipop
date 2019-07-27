package raft.debug

import cats.Applicative
import cats.implicits._
import org.slf4j.LoggerFactory
import raft.algebra.event.EventsLogger
import raft.model._

class Slf4jLogger[F[_]: Applicative, Cmd, State] extends EventsLogger[F, Cmd, State] {
  private val logger = LoggerFactory.getLogger(this.getClass)

  implicit def unitToF(u: Unit): F[Unit] = u.pure[F]

  override def receivedClientCmd(cmd: Cmd): F[Unit] = logger.info(s"Received command $cmd from client")

  override def replyClientWriteReq(req: Cmd, res: WriteResponse): F[Unit] =
    logger.info(s"Replied client, incoming cmd = $req, response = $res")

  override def receivedClientRead: F[Unit] = logger.debug("Received client read request")

  override def replyClientRead(res: ReadResponse[State]): F[Unit] = logger.debug(s"Replied read request with $res")

  override def voteRPCStarted(voteRequest: VoteRequest, receiverId: String): F[Unit] =
    logger.info(s"Vote RPC started, sent $voteRequest to $receiverId")

  override def voteRPCReplied(voteRequest: VoteRequest, response: VoteResponse): F[Unit] =
    logger.info(s"Replied voteRequest $voteRequest with $response")

  override def voteRPCEnded(voteRequest: VoteRequest, receiverId: String, response: VoteResponse): F[Unit] =
    logger.info(s"VoteRPC ended by $voteRequest to $receiverId and got back $response")

  override def elected(term: Int, lastLog: Option[Int]): F[Unit] =
    logger.info(s"Elected as leader of $term, lastLog = $lastLog")

  override def appendRPCStarted(request: AppendRequest[Cmd], receiverId: String): F[Unit] =
    logger.info(s"Start append RPC, $request to $receiverId")

  override def appendRPCReplied(request: AppendRequest[Cmd], response: AppendResponse): F[Unit] =
    logger.info(s"Replied appendRPC to $request wtih $response")

  override def appendRPCEnded(request: AppendRequest[Cmd], response: AppendResponse): F[Unit] =
    logger.info(s"Append RPC ended by $request by $response")

  override def logCommittedAndExecuted(idx: Int, cmd: Cmd, latest: State): F[Unit] =
    logger.info(s"Log committed at $idx with $cmd, lastest state = $latest")

  override def errorLogs(message: String): F[Unit] = logger.error(message)

  override def processTerminated: F[Unit] = logger.warn("Tada, terminating....")
}
