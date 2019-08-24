package raft
package setup

import cats.Functor
import cats.effect.concurrent.Ref
import raft.algebra.io.LogsApi
import raft.model.RaftLog

class TestLogsIO[F[_]: Functor, Inner](ref: Ref[F, Seq[RaftLog[Inner]]]) extends LogsApi[F, Inner] {
  override def getByIdx(idx: Int): F[Option[Log]] = ref.get.map(_.find(_.idx == idx))

  override def overwrite(logs: Seq[Log]): F[Unit] = ref.update { existing =>
    logs.headOption match {
      case Some(h) => existing.takeWhile(_.idx < h.idx) ++ logs
      case None => existing
    }

  }

  override def lastLog: F[Option[Log]] = ref.get.map(_.lastOption)

  /**
    * @param idx - inclusive
    */
  override def takeFrom(idx: Int): F[Seq[Log]] = ref.get.map(_.dropWhile(_.idx < idx))

  override def append(log: Log): F[Unit] = ref.update(_ :+ log)
}
