package raft
package algebra.client

import cats._
import cats.effect._
import fs2.Stream
import raft.algebra.append.BroadcastAppend
import raft.algebra.event.{ EventLogger, RPCTaskScheduler }
import raft.model._

@SuppressWarnings(Array("org.wartremover.warts.Any"))
class ClientWriteImpl[F[_]: Concurrent, Cmd: Eq](
  allState: RaftNodeState[F, Cmd],
  broadcast: BroadcastAppend[F],
  getCommittedStream: () => F[Stream[F, Cmd]],
  rpcScheduler: RPCTaskScheduler[F],
  eventLogger: EventLogger[F, Cmd, _]
) extends ClientWrite[F, Cmd] {
  // todo: Consider rework the implementation to be queue based?
  // Pros: the rest of the component are queue based, so it is more consistent
  // Cons: this will push the responsibility of replying to client else where
  def write(cmd: Cmd): F[ClientResponse] = {
    for {
      _         <- eventLogger.receivedClientReq(cmd)
      serverTpe <- allState.serverTpe.get
      res <- serverTpe match {
              case _: Leader =>
                val dispatchReq: F[Unit] = for {
                  _          <- appendToLocalLog(cmd)
                  reqPerNode <- broadcast.replicateLogs
                  _ <- reqPerNode.toList.traverse {
                        case (nodeId, task) => rpcScheduler.register(nodeId, task)
                      }
                } yield ()

                for {
                  stream <- getCommittedStream()
                  _      <- dispatchReq
                  _ <- stream
                        .find(_ === cmd)
                        .compile
                        .lastOrError
                } yield CommandCommitted: ClientResponse

              case _: Candidate => Monad[F].pure[ClientResponse](NoLeader)
              case f: Follower =>
                f.leaderId
                  .fold[ClientResponse](NoLeader)(RedirectTo)
                  .pure[F]
            }
      _ <- eventLogger.replyClientReq(cmd, res)
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
