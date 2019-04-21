package raft
package algebra.client

import raft.model._

trait ClientIncoming[F[_], Cmd] {
  def incoming(cmd: Cmd): F[ClientResponse]
}
