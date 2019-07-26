package raft.algebra.io

import raft.model.Metadata

trait MetadataIO[F[_]] {

  // read does not need to be atomic
  def get: F[Metadata]

  /**
    * This method need to be thread safe
    *
    * possible implementation:
    *   - JVM FileLock
    *   - embedded database
    */
  def update(f: Metadata => Metadata): F[Unit]
}
