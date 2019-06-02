package raft
package algebra.event

import java.time.LocalTime

import cats.effect.concurrent.Ref
import cats.{ Applicative, Show }
import raft.model.{ AppendRequest, RaftNodeState, VoteRequest, WriteResponse }

@SuppressWarnings(Array("org.wartremover.warts.Any", "org.wartremover.warts.Null"))
class InMemEventLogger[F[_]: Applicative, Cmd: Show, State: Show](val nodeId: String, val logs: Ref[F, StringBuffer])
    extends EventLogger[F, Cmd, State] {

  private def add(s: String): F[Unit] = {
    logs.update(_.append(s"\n${LocalTime.now()} Node-$nodeId: $s"))
  }

  override def receivedClientReq(cmd: Cmd): F[Unit] = {
    add(s"Received ${cmd.show} from client")
  }
  override def replyClientWriteReq(req: Cmd, res: WriteResponse): F[Unit] = {
    add(s"Reply $res to client for $req")
  }

  override def electionStarted(term: Int, lastLogIdx: Int): F[Unit] =
    add(s"Starting election for term=$term, lastLogIdx=$lastLogIdx")

  override def candidateReceivedVote(voteRequest: VoteRequest, peerId: String): F[Unit] =
    add(s"Candidate received vote for ${voteRequest.show} from $peerId")

  override def candidateVoteRejected(voteRequest: VoteRequest, peerId: String): F[Unit] =
    add(s"Candidate vote rejected for ${voteRequest.show} from $peerId")

  override def grantedVote(voteRequest: VoteRequest): F[Unit] = add(s"Grant vote for ${voteRequest.show}")

  override def rejectedVote(voteRequest: VoteRequest): F[Unit] = add(s"Reject vote for ${voteRequest.show}")

  override def elected(term: Int, lastLog: Option[Int]): F[Unit] =
    add(s"Elected as leader of $term, lastLog=${lastLog.orNull}")

  override def replicationStarted(term: Int): F[Unit] = add(s"Start replication, term=$term")

  override def leaderAppendSucceeded(appendRequest: AppendRequest[Cmd], followerId: String): F[Unit] =
    add(s"Appended ${appendRequest.show} to $followerId")

  override def leaderAppendRejected(appendRequest: AppendRequest[Cmd], followerId: String): F[Unit] =
    add(s"Fail to append ${appendRequest.show} to $followerId")

  override def acceptedLog(appendRequest: AppendRequest[Cmd], state: RaftNodeState[F, Cmd]): F[Unit] =
    add(s"Accepted log from ${appendRequest.show}")

  override def rejectedLog(appendRequest: AppendRequest[Cmd], state: RaftNodeState[F, Cmd]): F[Unit] =
    add(s"Rejected log from ${appendRequest.show}")

  override def logCommitted(idx: Int, cmd: Cmd): F[Unit] = add(s"Committed idx=$idx, cmd=$cmd")

  override def stateUpdated(state: State): F[Unit] = add(s"State updated to ${state.show}")

  override def errorLogs(message: String): F[Unit] = add(message)
}
