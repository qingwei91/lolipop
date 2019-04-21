package raft
package algebra.client

import cats.{ Eq, MonadError }
import cats.effect.Concurrent
import fs2.Stream
import fs2.concurrent.{ Queue, Topic }
import org.slf4j.LoggerFactory
import raft.algebra.append.BroadcastAppend
import raft.model._

@SuppressWarnings(Array("org.wartremover.warts.Any"))
class ClientIncomingImpl[F[_]: Concurrent, Cmd: Eq](
  allState: RaftNodeState[F, Cmd],
  broadcast: BroadcastAppend[F],
  committedCmd: Topic[F, Cmd],
  replicateLogQueue: Queue[F, F[Unit]]
)(implicit F: MonadError[F, Throwable])
    extends ClientIncoming[F, Cmd] {

  private val logger = LoggerFactory.getLogger(s"${getClass.getSimpleName}.${allState.config.nodeId}")

  def incoming(cmd: Cmd): F[ClientResponse] = {
    for {
      serverTpe <- allState.serverTpe.get
      res <- serverTpe match {
              case _: Leader =>
                val dispatchReq: F[Unit] = for {
                  _ <- appendToLocalLog(cmd)
                  _ = logger.info("Initiate replication by client")
                  _ <- replicateLogQueue.enqueue1(broadcast.replicateLogs)
                } yield ()

                val committed: Stream[F, ClientResponse] = committedCmd
                  .subscribe(100)
                  .find(_ === cmd)
                  .concurrently(Stream.eval(dispatchReq))
                  .as {
                    logger.info(s"Reply to client for $cmd")
                    CommandCommitted: ClientResponse
                  }

                committed.compile.lastOrError
              case _: Candidate =>
                logger.info("No leader yet")
                F.pure[ClientResponse](NoLeader)
              case f: Follower =>
                logger.info("Not leader")
                f.leaderId
                  .fold[ClientResponse](NoLeader)(RedirectTo)
                  .pure[F]
            }
    } yield res
  }

  private def appendToLocalLog(cmd: Cmd): F[Unit] = {
    allState.persistent.update { p =>
      val last = p.logs.lastOption.map(_.idx + 1).getOrElse(1)
      val next = RaftLog(last, p.currentTerm, cmd)
      p.copy(logs = p.logs :+ next)
    }
  }
}
