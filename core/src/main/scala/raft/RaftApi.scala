package raft

import raft.algebra.append.AppendRPCHandler
import raft.algebra.client.ClientIncoming
import raft.algebra.election.VoteRPCHandler
import raft.model.RaftLog

trait RaftApi[F[_], Cmd] extends ClientIncoming[F, Cmd] with AppendRPCHandler[F, RaftLog[Cmd]] with VoteRPCHandler[F]
