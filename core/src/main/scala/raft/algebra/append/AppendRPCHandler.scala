package raft
package algebra.append

import raft.model.{ AppendRequest, AppendResponse }

trait AppendRPCHandler[F[_], Cmd] {
  def requestAppend(req: AppendRequest[Cmd]): F[AppendResponse]
}
