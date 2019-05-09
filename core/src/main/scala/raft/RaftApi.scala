package raft

import raft.algebra.append.AppendRPCHandler
import raft.algebra.client.ClientIncoming
import raft.algebra.election.VoteRPCHandler

trait RaftApi[F[_], Cmd] extends ClientIncoming[F, Cmd] with AppendRPCHandler[F, Cmd] with VoteRPCHandler[F]
