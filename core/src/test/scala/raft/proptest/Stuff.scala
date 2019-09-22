package raft.proptest

import raft.algebra.StateMachine
import raft.algebra.append.AppendRPCHandler
import raft.algebra.election.VoteRPCHandler
import raft.algebra.event.EventsLogger
import raft.model.RaftNodeState

// terribly named, and confusing construct :(
// this is just a bag of dependencies needed when creating cluster
// we can totally uses Tuple5, but having a nominal type makes life
// easier
case class Stuff[F[_], Cmd, St](
  sm: StateMachine[F, Cmd, St],
  nodeS: RaftNodeState[F, Cmd],
  append: AppendRPCHandler[F, Cmd],
  vote: VoteRPCHandler[F],
  logger: EventsLogger[F, Cmd, St]
)
