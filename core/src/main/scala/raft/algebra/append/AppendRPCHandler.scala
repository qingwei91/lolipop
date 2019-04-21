package raft
package algebra.append

import raft.model.{ AppendRequest, AppendResponse }

trait AppendRPCHandler[F[_], Log] {
  def requestAppend(req: AppendRequest[Log]): F[AppendResponse]
}
