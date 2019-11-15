package raft
package algebra
package client

import cats.Monad
import raft.algebra.event.EventsLogger
import raft.model._

class ClientReadImpl[F[_]: Monad, Cmd, State](
  stateMachine: StateMachine[F, Cmd, State],
  allState: RaftNodeState[F, _],
  eventLogger: EventsLogger[F, _, State]
) extends ClientRead[F, Cmd, State] {
  override def read(readCmd: Cmd): F[ReadResponse[State]] = {
    for {
      _         <- eventLogger.receivedClientRead
      serverTpe <- allState.serverTpe.get
      res <- serverTpe match {
              // WARNING: using stateMachine.execute here is dangerous
              // it assumes the cmd is a read-only, for now it works by
              // we can fix it by having another type for ReadCmd/ReadQuery
              case _: Leader => stateMachine.execute(readCmd).map(s => Read(s): ReadResponse[State])
              case _: Candidate => Monad[F].pure[ReadResponse[State]](NoLeader)
              case f: Follower =>
                f.leaderId
                  .fold[ReadResponse[State]](NoLeader)(RedirectTo)
                  .pure[F]
            }
      _ <- eventLogger.replyClientRead(res)
    } yield res
  }
}
