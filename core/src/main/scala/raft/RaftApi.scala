package raft

import raft.algebra.append.AppendRPCHandler
import raft.algebra.client.{ ClientRead, ClientWrite }
import raft.algebra.election.VoteRPCHandler

trait RaftApi[F[_], Cmd, State]
    extends ClientWrite[F, Cmd]
    with ClientRead[F, State]
    with AppendRPCHandler[F, Cmd]
    with VoteRPCHandler[F]
