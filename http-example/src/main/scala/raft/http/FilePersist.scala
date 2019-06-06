package raft.http

import cats.effect.IO
import cats.effect.concurrent.MVar
import raft.model.{ Persistent, PersistentIO }

class FilePersist(db: swaydb.Map[Int, Persistent, IO], lock: MVar[IO, Unit]) extends PersistentIO[IO] {
  val singleKey = 1

  @SuppressWarnings(Array("org.wartremover.warts.OptionPartial"))
  override def get: IO[Persistent] = db.get(singleKey).map(_.get)

  /**
    * The implementation of this method must persist
    * the `Persistent` atomically
    *
    * possible implementation:
    *   - JVM FileLock
    *   - embedded database
    */
  override def update(f: Persistent => Persistent): IO[Unit] =
    for {
      _   <- lock.take
      old <- get
      new_ = f(old)
      _ <- db.put(singleKey, new_)
      _ <- lock.put(())
    } yield ()
}
