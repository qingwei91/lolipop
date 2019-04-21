package raft.setup

import cats.effect.IO
import cats.effect.concurrent.{ MVar, Ref }
import raft.model._

case class TestState(
  config: ClusterConfig,
  persistent: Ref[IO, Persistent[RaftLog[String]]],
  serverTpe: Ref[IO, ServerType],
  serverTpeLock: MVar[IO, Unit],
  debugCounter: Ref[IO, Int]
) extends RaftNodeState[IO, String]
