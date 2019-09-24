package raft
package proptest
package checker

import cats.{ Eq, MonadError, Parallel }

object LinearizationCheck {

  case class WGConfig[F[_], O, R, S](history: History[F, O, R], minOpsIdx: Int, state: S)
  sealed trait Hint
  case object Cont extends Hint
  case object OK extends Hint
  case object NOK extends Hint

  def wingAndGong[F[_], Op, Re: Eq, St](history: History[F, Op, Re], model: Model[F, Op, Re, St], st: St)(
    implicit F: MonadError[F, Throwable]
  ): F[Boolean] = {
    type Stack = List[WGConfig[F, Op, Re, St]]

    val init = List(WGConfig(history, 0, st))

    F.iterateUntilM[(Stack, Hint)](init -> Cont) {
        case p @ (_, NOK) => F.pure(p)
        case p @ (_, OK) => F.pure(p)
        case (Nil, Cont) => F.pure(Nil -> NOK)
        case p @ (WGConfig(hist, minOpsIdx, state) :: stackTail, Cont) =>
          if (hist.finished) {
            F.pure(p._1 -> OK)
          } else {
            val minOp = hist.minimumOps.get(minOpsIdx.toLong)

            println(s"Pick $minOp among ${hist.minimumOps}")

            minOp match {
              case None =>
                stackTail match {
                  case Nil => F.pure(Nil -> NOK) // nothing to branch, fail
                  case prevHead :: tail =>
                    // change minOps which change the branch
                    // prevHead.minOps is not empty because if it is
                    // empty it will be dropped earlier
                    val dropMinOp = prevHead.minOpsIdx + 1
                    val amended   = prevHead.copy(minOpsIdx = dropMinOp)
                    F.pure(
                      (amended :: tail) -> Cont
                    )
                }
              case Some(e @ Invoke(_, op)) =>
                for {
                  pair <- model.step(state, op)
                  (next, expected) = pair
                  actual <- hist.ret(e)
                } yield {
                  if (actual.result === expected) {
                    println(s"Linearize $e -- $next")
                    val nextHis   = hist.linearize(e)
                    val nextEle   = WGConfig(nextHis, 0, next)
                    val nextStack = nextEle :: p._1
                    nextStack -> Cont
                  } else {

                    // if current op does not work, try the other op
                    // in the same config
                    val replacingEle = WGConfig(hist, minOpsIdx + 1, state)

                    println(s"Fail to linearize $e - backtrack to $replacingEle")

                    (replacingEle :: stackTail) -> Cont
                  }
                }
            }
          }
      } {
        case (_, NOK) => true
        case (_, OK) => true
        case _ => false
      }
      .flatMap {
        case (_, NOK) => false.pure[F]
        case (_, OK) => true.pure[F]
        case (stack, Cont) =>
          F.raiseError[Boolean](
            new RuntimeException(s"Unexpected, got `Cont` after iteration terminates, stack = $stack")
          )
      }
  }

  def jitLinearize[F[_], Op, Re: Eq, St](history: History[F, Op, Re], model: Model[F, Op, Re, St], st: St)(
    implicit F: MonadError[F, Throwable]
  ): F[Boolean] = {
    ???
  }

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
