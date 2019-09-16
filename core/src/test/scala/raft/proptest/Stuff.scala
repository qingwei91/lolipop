package raft.proptest

import raft.algebra.StateMachine
import raft.algebra.append.AppendRPCHandler
import raft.algebra.election.VoteRPCHandler
import raft.algebra.event.EventsLogger
import raft.model.RaftNodeState

case class Stuff[F[_], Cmd, St](
  sm: StateMachine[F, Cmd, St],
  nodeS: RaftNodeState[F, Cmd],
  append: AppendRPCHandler[F, Cmd],
  vote: VoteRPCHandler[F],
  logger: EventsLogger[F, Cmd, St]
)
