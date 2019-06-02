package raft
package algebra
package client

import cats.Monad
import raft.model._

class ClientReadImpl[F[_]: Monad, State](queryState: QueryState[F, State], allState: RaftNodeState[F, _])
    extends ClientRead[F, State] {
  override def read: F[ReadResponse[State]] = {
    for {
      serverTpe <- allState.serverTpe.get
      res <- serverTpe match {
              case _: Leader => queryState.getCurrent.map(s => Read(s): ReadResponse[State])
              case _: Candidate => Monad[F].pure[ReadResponse[State]](NoLeader)
              case f: Follower =>
                f.leaderId
                  .fold[ReadResponse[State]](NoLeader)(RedirectTo)
                  .pure[F]
            }
    } yield res
  }
}
