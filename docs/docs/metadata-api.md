---
layout: docs
title: Metadata Api
section: "usage"
---

### 4. Implement `MetadataIO`

Each raft node need to persist some metadata, the interaction with these metadata is a lot simpler compared to logs, it's basic get and put, it is represented by `Metadata`

Here's an example
```scala
import cats.Monad
import cats.effect.concurrent.MVar
import raft.algebra.io.MetadataIO
import raft.model.Metadata

class SwayDBPersist[F[_]: Monad](db: swaydb.Map[Int, Metadata, F], lock: MVar[F, Unit]) extends MetadataIO[F] {
  val singleKey = 1

  override def get: F[Metadata] = db.get(singleKey).map(_.get)

  /**
    * The implementatFn of this method must persist
    * the `Persistent` atomically
    *
    * possible implementatFn:
    *   - JVM FileLock
    *   - embedded database
    */
  override def update(f: Metadata => Metadata): F[Unit] =
    for {
      _   <- lock.take
      old <- get
      new_ = f(old)
      _ <- db.put(singleKey, new_)
      _ <- lock.put(())
    } yield ()
}
```
