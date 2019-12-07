package raft.setup

import cats.effect.concurrent.Ref
import raft.algebra.io.MetadataIO
import raft.model.Metadata

class InMemMetadata[F[_]](ref: Ref[F, Metadata]) extends MetadataIO[F] {
  override def get: F[Metadata] = ref.get

  /**
    * The implementation of this method must persist
    * `Metadata` atomically
    *
    * possible implementation:
    *   - JVM FileLock
    *   - embedded database
    */
  override def update(f: Metadata => Metadata): F[Unit] = ref.update(f)
}
