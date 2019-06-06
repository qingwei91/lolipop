package raft
package model

case class Persistent(currentTerm: Int, votedFor: Option[String])

trait PersistentIO[F[_]] {

  // read does not have to be atomic
  def get: F[Persistent]

  /**
    * The implementation of this method must persist
    * the `Persistent` atomically
    *
    * possible implementation:
    *   - JVM FileLock
    *   - embedded database
    */
  def update(f: Persistent => Persistent): F[Unit]
}

object Persistent {
  def init = Persistent(
    0,
    None
  )
}
