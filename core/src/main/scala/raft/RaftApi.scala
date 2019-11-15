package raft

import raft.algebra.append.AppendRPCHandler
import raft.algebra.client.{ ClientRead, ClientWrite }
import raft.algebra.election.VoteRPCHandler

trait RaftApi[F[_], Cmd, Res]
    extends ClientWrite[F, Cmd, Res]
    with ClientRead[F, Cmd, Res]
    with AppendRPCHandler[F, Cmd]
    with VoteRPCHandler[F]
