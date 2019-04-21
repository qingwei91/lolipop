package raft.setup

import cats.effect.IO
import cats.effect.concurrent.Ref
import raft.algebra.io.LogIO
import raft.model.RaftLog

class TestLogsIO(ref: Ref[IO, Seq[RaftLog[String]]]) extends LogIO[IO, String] {
  override def getByIdx(idx: Int): IO[Option[Log]] = ref.get.map(_.find(_.idx == idx))

  override def overwrite(logs: Seq[Log]): IO[Unit] = ref.update { existing =>
    logs.headOption match {
      case Some(h) => existing.takeWhile(_.idx < h.idx) ++ logs
      case None => existing
    }

  }

  override def lastLog: IO[Option[Log]] = ref.get.map(_.lastOption)

  /**
    * @param idx - inclusive
    */
  override def takeFrom(idx: Int): IO[Seq[Log]] = ref.get.map(_.dropWhile(_.idx < idx))

  override def append(log: Log): IO[Unit] = ref.update(_ :+ log)
}
