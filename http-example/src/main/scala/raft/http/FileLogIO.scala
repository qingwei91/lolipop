package raft.http

import cats.effect.IO
import raft.algebra.io.LogIO
import raft.model.RaftLog

class FileLogIO(db: swaydb.Map[Int, RaftLog[ChangeCount], IO]) extends LogIO[IO, ChangeCount] {

  override def getByIdx(idx: Int): IO[Option[Log]] = db.get(idx)

  override def overwrite(logs: Seq[Log]): IO[Unit] = {
    db.put(logs.map(s => s.idx -> s)).map(_ => ())
  }

  override def lastLog: IO[Option[Log]] = db.lastOption.map { opt =>
    opt.map(_._2)
  }

  /**
    * @param idx - inclusive
    */
  override def takeFrom(idx: Int): IO[Seq[Log]] = {
    db.fromOrAfter(idx).takeWhile(_ => true).map(_._2).materialize
  }

  override def append(log: Log): IO[Unit] = db.put(log.idx, log).map(_ => ())
}
