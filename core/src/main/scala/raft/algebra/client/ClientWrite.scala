package raft
package algebra.client

import raft.model._

trait ClientWrite[F[_], Cmd, Res] {
  def write(cmd: Cmd): F[WriteResponse[Res]]
}
