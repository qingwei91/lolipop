package raft
package util

import cats.Monad
import cats.effect.{ ContextShift, Timer }

import scala.concurrent.duration.FiniteDuration

object Retry {
  implicit class RetryOps[F[_], A, B](val fa: F[Either[A, B]]) extends AnyVal {
    // todo: rethink on retry, should retry a bigger chunk to avoid race condition
    def retry(
      n: Int,
      backoff: FiniteDuration
    )(implicit F: Monad[F], T: Timer[F], CS: ContextShift[F]): F[Either[A, B]] = {
      CS.shift *>
      fa.flatMap {
        case Left(_) if n > 0 => T.sleep(backoff) *> retry(n - 1, backoff * 2)
        case r => F.pure(r)
      }
    }
  }
}
