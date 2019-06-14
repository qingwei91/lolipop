package raft
package setup

import raft.algebra.event.EventLogger
import raft.model.RaftNodeState

// A class to hold things needed in test cases
case class RaftTestComponents[F[_]](
  proc: RaftProcess[F, String, String],
  state: RaftNodeState[F, String],
  eventLogger: EventLogger[F, String, String]
) {
  def api: RaftApi[F, String, String] = proc.api
}
