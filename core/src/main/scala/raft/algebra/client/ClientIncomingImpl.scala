package raft
package algebra.client

import cats.effect.Concurrent
import cats._
import fs2.Stream
import fs2.concurrent.Topic
import raft.algebra.append.BroadcastAppend
import raft.algebra.event.{ EventLogger, RPCTaskScheduler }
import raft.model._

@SuppressWarnings(Array("org.wartremover.warts.Any"))
class ClientIncomingImpl[F[_]: Concurrent, FF[_], Cmd: Eq](
  allState: RaftNodeState[F, Cmd],
  broadcast: BroadcastAppend[F],
  committedCmd: Topic[F, Cmd],
  rpcScheduler: RPCTaskScheduler[F],
  eventLogger: EventLogger[F, Cmd, _]
)(implicit F: MonadError[F, Throwable], P: Parallel[F, FF])
    extends ClientIncoming[F, Cmd] {

  // todo: Consider rework the implementation to be queue based?
  // Pros: the rest of the component are queue based, so it is more consistent
  // Cons: this will push the responsibility of replying to client else where
  def incoming(cmd: Cmd): F[ClientResponse] = {
    for {
      _         <- eventLogger.receivedClientReq(cmd)
      serverTpe <- allState.serverTpe.get
      res <- serverTpe match {
              case _: Leader =>
                val dispatchReq: F[Unit] = for {
                  _          <- appendToLocalLog(cmd)
                  reqPerNode <- broadcast.replicateLogs
                  _ <- reqPerNode.toList.parTraverse {
                        case (nodeId, task) => rpcScheduler.register(nodeId, task)
                      }
                } yield ()

                val committed: Stream[F, ClientResponse] = committedCmd
                  .subscribe(100)
                  .find(_ === cmd)
                  .concurrently(Stream.eval(dispatchReq))
                  .as {
                    CommandCommitted: ClientResponse
                  }

                committed.compile.lastOrError
              case _: Candidate => F.pure[ClientResponse](NoLeader)
              case f: Follower =>
                f.leaderId
                  .fold[ClientResponse](NoLeader)(RedirectTo)
                  .pure[F]
            }
    } yield res
  }

  private def appendToLocalLog(cmd: Cmd): F[Unit] = allState.serverTpeMutex {
    for {
      persistent <- allState.persistent.get
      lastLog    <- allState.logs.lastLog
      nextIdx = lastLog.map(_.idx + 1).getOrElse(1)
      next    = RaftLog(nextIdx, persistent.currentTerm, cmd)
      _ <- allState.logs.append(next)
    } yield ()
  }
}
