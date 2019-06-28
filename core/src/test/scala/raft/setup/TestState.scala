package raft.setup

import cats.effect.concurrent.{ MVar, Ref }
import raft.algebra.io.{ LogsApi, MetadataIO }
import raft.model._

case class TestState[F[_]](
                            config: ClusterConfig,
                            metadata: MetadataIO[F],
                            serverTpe: Ref[F, ServerType],
                            serverTpeLock: MVar[F, Unit],
                            logs: LogsApi[F, String]
) extends RaftNodeState[F, String]
