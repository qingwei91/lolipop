package raft

import cats.CommutativeApplicative
import cats.effect.IO

package object proptest {

  implicit val ioCommutes: CommutativeApplicative[IO] = new CommutativeApplicative[IO] {
    override def pure[A](x: A): IO[A] = IO.pure(x)

    override def ap[A, B](ff: IO[A => B])(fa: IO[A]): IO[B] =
      ff.flatMap(f => fa.map(f))
  }
}
