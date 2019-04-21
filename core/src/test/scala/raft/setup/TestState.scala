package raft.setup

import cats.effect.IO
import cats.effect.concurrent.{ MVar, Ref }
import raft.algebra.io.LogIO
import raft.model._

case class TestState(
  config: ClusterConfig,
  persistent: Ref[IO, Persistent],
  serverTpe: Ref[IO, ServerType],
  serverTpeLock: MVar[IO, Unit],
  logs: LogIO[IO, String]
) extends RaftNodeState[IO, String]
