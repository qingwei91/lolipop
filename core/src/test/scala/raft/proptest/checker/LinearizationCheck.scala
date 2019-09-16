package raft
package proptest
package checker

import cats.{ Eq, MonadError, Parallel }

object LinearizationCheck {

  /**
    * history + model => analysis
    *
    * 1. linearize history (NP hard problem)
    * 2. use the history, replay it with model
    * 3.
    */
  def analyse[F[_], FF[_], Op, Res: Eq, St](history: List[Event[Op, Res]], model: Model[F, Op, Res, St], st: St)(
    implicit Par: Parallel[F, FF],
    F: MonadError[F, Throwable]
  ): F[Boolean] = {

    def loop(subHistory: List[Event[Op, Res]], st: St): F[Boolean] = {
      subHistory match {
        case Invoke(_, op) :: Ret(_, result) :: tail =>
          for {
            pair <- model.step(st, op)
            (st, expected) = pair
            r <- if (expected === result) {
                  loop(tail, st)
                } else {
                  false.pure[F]
                }
          } yield r
        case Invoke(_, op) :: Failure(_, _) :: tail =>
          /**
            * When failure, the operation may either took place or
            * did not take place, so we fork into both possibility
            * and continue our search
            * TODO: this seems inefficient, try to optimize
            */
          for {
            pair <- model.step(st, op)
            (newSt, _) = pair
            r <- (loop(tail, newSt), loop(tail, st)).parMapN {
                  case (a, b) => a || b
                }
          } yield r

        case Nil => true.pure[F]
        case other => F.raiseError(new Exception(s"Unexpected incomplete history ${other.take(2)}"))
      }
    }
    loop(history, st)
  }
}
