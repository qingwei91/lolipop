package raft
package algebra.event

import cats.{ Applicative, Show }
import org.slf4j.LoggerFactory
import raft.model._

@SuppressWarnings(Array("org.wartremover.warts.Any", "org.wartremover.warts.Null"))
class Slf4jEventLogger[F[_]: Applicative, Cmd: Show, State: Show](nodeId: String) extends EventLogger[F, Cmd, State] {
  private implicit def liftToF(a: Unit): F[Unit] = a.pure[F]
  private val logger                             = LoggerFactory.getLogger(s"Slf4jEventLogger-$nodeId")

  override def receivedClientReq(cmd: Cmd): F[Unit] = logger.info(s"Received ${cmd.show} from client")

  override def replyClientWriteReq(req: Cmd, res: WriteResponse): F[Unit] =
    logger.info(s"Reply $res to client for $req")

  override def electionStarted(term: Int, lastLogIdx: Int): F[Unit] =
    logger.info(s"Starting election for term=$term, lastLogIdx=$lastLogIdx")

  override def candidateReceivedVote(voteRequest: VoteRequest, peerId: String): F[Unit] =
    logger.info(s"Candidate received vote for ${voteRequest.show} from $peerId")

  override def candidateVoteRejected(voteRequest: VoteRequest, peerId: String): F[Unit] =
    logger.info(s"Candidate vote rejected for ${voteRequest.show} from $peerId")

  override def grantedVote(voteRequest: VoteRequest): F[Unit] = logger.info(s"Grant vote for ${voteRequest.show}")

  override def rejectedVote(voteRequest: VoteRequest): F[Unit] = logger.info(s"Reject vote for ${voteRequest.show}")

  override def elected(term: Int, lastLog: Option[Int]): F[Unit] =
    logger.info(s"Elected as leader of $term, lastLog=${lastLog.orNull}")

  override def replicationStarted(term: Int): F[Unit] = logger.info(s"Start replication, term=$term")

  override def leaderAppendSucceeded(appendRequest: AppendRequest[Cmd], followerId: String): F[Unit] =
    logger.info(s"Appended ${appendRequest.show} to $followerId")

  override def leaderAppendRejected(appendRequest: AppendRequest[Cmd], followerId: String): F[Unit] =
    logger.warn(s"Fail to append ${appendRequest.show} to $followerId")

  override def acceptedLog(appendRequest: AppendRequest[Cmd], state: RaftNodeState[F, Cmd]): F[Unit] =
    logger.info(s"Accepted log from ${appendRequest.show}")

  override def rejectedLog(appendRequest: AppendRequest[Cmd], state: RaftNodeState[F, Cmd]): F[Unit] =
    logger.warn(s"Rejected log from ${appendRequest.show}")

  override def logCommitted(idx: Int, cmd: Cmd): F[Unit] = logger.warn(s"Committed idx=$idx, cmd=$cmd")

  override def stateUpdated(state: State): F[Unit] = logger.warn(s"State updated to ${state.show}")

  override def errorLogs(message: String): F[Unit] = logger.error(message)
}
