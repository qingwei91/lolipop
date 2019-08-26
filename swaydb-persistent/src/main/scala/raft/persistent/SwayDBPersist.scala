package raft
package persistent

import cats.Monad
import cats.effect.concurrent.MVar
import raft.algebra.io.MetadataIO
import raft.model.Metadata

class SwayDBPersist[F[_]: Monad](db: swaydb.Map[Int, Metadata, F], lock: MVar[F, Unit]) extends MetadataIO[F] {
  val singleKey = 1

  @SuppressWarnings(Array("org.wartremover.warts.OptionPartial"))
  override def get: F[Metadata] = db.get(singleKey).map(_.get)

  override def update(f: Metadata => Metadata): F[Unit] =
    modify { m =>
      (f(m), ())
    }

  override def modify[B](f: Metadata => (Metadata, B)): F[B] =
    for {
      _   <- lock.take
      old <- get
      (new_, b) = f(old)
      _ <- db.put(singleKey, new_)
      _ <- lock.put(())
    } yield b

}
