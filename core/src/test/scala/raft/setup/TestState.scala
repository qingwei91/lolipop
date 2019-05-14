package raft.setup

import cats.effect.concurrent.{ MVar, Ref }
import raft.algebra.io.LogIO
import raft.model._

case class TestState[F[_]](
  config: ClusterConfig,
  persistent: Ref[F, Persistent],
  serverTpe: Ref[F, ServerType],
  serverTpeLock: MVar[F, Unit],
  logs: LogIO[F, String]
) extends RaftNodeState[F, String]
