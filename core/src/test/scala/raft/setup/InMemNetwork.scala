package raft.setup

import cats.Monad
import cats.effect.ContextShift
import cats.implicits._
import org.slf4j.LoggerFactory
import raft.algebra.append.AppendRPCHandler
import raft.algebra.election.VoteRPCHandlerImpl
import raft.algebra.io.NetworkIO
import raft.model._

class InMemNetwork[F[_]: ContextShift: Monad, State](
  appendResponders: Map[String, AppendRPCHandler[F, RaftLog[String]]],
  voteResponders: Map[String, VoteRPCHandlerImpl[F, String]]
) extends NetworkIO[F, RaftLog[String]] {

  val logger = LoggerFactory.getLogger(s"${getClass.getSimpleName}")

  override def sendAppendRequest(nodeID: String, appendReq: AppendRequest[RaftLog[String]]): F[AppendResponse] = {
    ContextShift[F].shift *> appendResponders(nodeID).requestAppend(appendReq)
  }

  override def sendVoteRequest(nodeID: String, voteRq: VoteRequest): F[VoteResponse] = {
    ContextShift[F].shift *> voteResponders(nodeID).requestVote(voteRq)
  }
}
