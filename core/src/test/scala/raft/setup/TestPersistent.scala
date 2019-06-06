package raft.setup

import cats.effect.concurrent.Ref
import raft.model.{ Persistent, PersistentIO }

class TestPersistent[F[_]](ref: Ref[F, Persistent]) extends PersistentIO[F] {
  override def get: F[Persistent] = ref.get

  /**
    * The implementation of this method must persist
    * the `Persistent` atomically
    *
    * possible implementation:
    *   - JVM FileLock
    *   - embedded database
    */
  override def update(f: Persistent => Persistent): F[Unit] = ref.update(f)
}
