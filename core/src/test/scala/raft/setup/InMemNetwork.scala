package raft.setup

import cats.Monad
import cats.effect.ContextShift
import cats.implicits._
import org.slf4j.LoggerFactory
import raft.algebra.append.AppendRPCHandler
import raft.algebra.election.VoteRPCHandler
import raft.algebra.io.NetworkIO
import raft.model._

class InMemNetwork[F[_]: ContextShift: Monad, Cmd, State](
  appendResponders: Map[String, AppendRPCHandler[F, Cmd]],
  voteResponders: Map[String, VoteRPCHandler[F]]
) extends NetworkIO[F, Cmd] {

  val logger = LoggerFactory.getLogger(s"${getClass.getSimpleName}")

  override def sendAppendRequest(nodeID: String, appendReq: AppendRequest[Cmd]): F[AppendResponse] = {
    ContextShift[F].shift *> appendResponders(nodeID).requestAppend(appendReq)
  }

  override def sendVoteRequest(nodeID: String, voteRq: VoteRequest): F[VoteResponse] = {
    ContextShift[F].shift *> voteResponders(nodeID).requestVote(voteRq)
  }
}
