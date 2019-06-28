---
layout: docs
title: Logs Api
section: "usage"
---

### 2. Implement `LogsApi`

Raft algorithm reach consensus by replicating logs in the cluster, and logs are expected to be durable so that consensus are maintain in spite of server crash.

`LogsApi` represent the ability for the application to interact with logs, **Lolipop** let user to implement this because there are different ways to achieve durable logs (eg. file system, remote database).   

Below is an example implemented using [SwayDB](http://www.swaydb.io/)

```scala
import cats.effect.IO
import raft.algebra.io.LogsApi
import raft.model.RaftLog

class FileLogIO(db: swaydb.Map[Int, RaftLog[ChangeCount], IO]) extends LogsApi[IO, ChangeCount] {

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
```
