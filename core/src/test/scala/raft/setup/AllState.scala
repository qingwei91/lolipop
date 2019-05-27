package raft.setup

import fs2.concurrent.{ Queue, Topic }
import raft.model.RaftNodeState

// A class to group states for convenience
case class AllState[F[_]](
  stateMachine: TestStateMachine[F],
  state: RaftNodeState[F, String],
  committed: Topic[F, String],
  clientReq: Queue[F, F[Unit]]
)
