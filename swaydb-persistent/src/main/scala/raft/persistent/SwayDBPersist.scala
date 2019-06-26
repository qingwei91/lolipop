package raft
package persistent

import cats.Monad
import cats.effect.concurrent.MVar
import raft.algebra.io.PersistentIO
import raft.model.Persistent

class SwayDBPersist[F[_]: Monad](db: swaydb.Map[Int, Persistent, F], lock: MVar[F, Unit]) extends PersistentIO[F] {
  val singleKey = 1

  @SuppressWarnings(Array("org.wartremover.warts.OptionPartial"))
  override def get: F[Persistent] = db.get(singleKey).map(_.get)

  /**
    * The implementatFn of this method must persist
    * the `Persistent` atomically
    *
    * possible implementatFn:
    *   - JVM FileLock
    *   - embedded database
    */
  override def update(f: Persistent => Persistent): F[Unit] =
    for {
      _   <- lock.take
      old <- get
      new_ = f(old)
      _ <- db.put(singleKey, new_)
      _ <- lock.put(())
    } yield ()
}
