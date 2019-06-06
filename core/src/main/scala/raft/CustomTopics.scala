package raft

import cats.effect._
import fs2.concurrent._

/**
  * We are using a custom interface because the one from fs2 is not able to
  * express the act of subscribing the topic without consuming the message
  *
  * ie. subscribe method from fs2.concurrent.Topic return Stream[F, A] instead
  * of F[Stream[F, A]
  */
trait CustomTopics[F[_], A] {
  def publish1(a: A): F[Unit]
  def subscribe(maxQueued: Int): Resource[F, fs2.Stream[F, A]]
}

object CustomTopics {
  import cats.effect.concurrent.Ref

  @SuppressWarnings(Array("org.wartremover.warts.All"))
  def apply[F[_], A](implicit F: Concurrent[F]): F[CustomTopics[F, A]] =
    Ref
      .of[F, List[fs2.concurrent.Queue[F, A]]](List.empty)
      .map(
        cache =>
          new CustomTopics[F, A] {

            override def publish1(a: A): F[Unit] =
              cache.get.flatMap { subscribers =>
                subscribers.traverse { q =>
                  q.enqueue1(a)
                }.void
              }

            override def subscribe(maxQueued: Int): Resource[F, fs2.Stream[F, A]] = {

              Resource
                .make {
                  for {
                    q <- Queue.bounded[F, A](maxQueued)
                    _ <- cache.update(_ :+ q)
                  } yield {
                    q
                  }
                }(q => cache.update(_.filter(_ ne q)))
                .map(_.dequeue)
            }
        }
      )
}
