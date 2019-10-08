package raft
package proptest
package checker

import cats.{ Eq, MonadError, Parallel }

object LinearizationCheck {

  case class WGConfig[F[_], O, R, S](history: History[F, O, R], minOpsIdx: Int, state: S)

  /**
    * this algorithm is a stacksafe implementation of W&G algo
    * which is recursive, the idea is to store states on heap
    * which is manually managed by the algo to simulate a stack
    *
    * The algo works like this
    * 1. Start with an empty stack and full history
    * 2. If hint is OK/NOK, return corresponding result
    * 3. If hint is Cont, check if history is finished, if  finished, return as OK
    * 4. If history not finished, get 1 minimal op from history, there can be multiple min-op, or no min-op, if no min op, it means the current config is not going to work, backtrack to previous config, but choose a different min-op
    * 5. Apply chosen min-op on model, check if model aligns to actual history, if so, pop a new config on stack and go to step 2
    */
  def wingAndGongStackSafe[F[_], Op, Re: Eq, St](history: History[F, Op, Re], model: Model[F, Op, Re, St], st: St)(
    implicit F: MonadError[F, Throwable]
  ): F[Boolean] = {

    /**
      * represent the result of W&G linearizability check
      * Cont means the the algorithm should proceed
      * Ok means it's finished and it is linearizable
      * NOK means it's finished and nonlinearizable
      */
    sealed trait Hint
    case object Cont extends Hint
    case object OK extends Hint
    case object NOK extends Hint

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
                  actual match {
                    case Right(r) =>
                      if (r.result === expected) {
                        val nextHis   = hist.linearize(e)
                        val nextEle   = WGConfig(nextHis, 0, next)
                        val nextStack = nextEle :: p._1
                        nextStack -> Cont
                      } else {
                        // if current op does not work, try the other op
                        // in the same config
                        val replacingEle = WGConfig(hist, minOpsIdx + 1, state)
                        (replacingEle :: stackTail) -> Cont
                      }
                    case Left(f) =>
                      println(f)
                      ???
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

  /**
    * The original form of W&G algo, it is recursive but not
    * tail-rec and thus not stack-safe, but I think it is fine as
    * the stackframes grow and shrink in the process and is
    * bounded to the length of input history rather than the
    * number of possible history.
    *
    * 1. Find min-op from history
    * 2. Apply min-op on model
    * 3. If model return match actual return, remove min-op from history, and go back to step 1
    * 4. If model return does not match actual return, blacklist the min-op and go to step 1
    * 5. If no min-op can be used, ie. all blacklisted or there's none, then it is not linearizable
    */
  def wingAndGongUnsafe[F[_], Op, Re: Eq, St](history: History[F, Op, Re], model: Model[F, Op, Re, St], st: St)(
    implicit F: MonadError[F, Throwable]
  ): F[Boolean] = {
    if (history.finished) {
      F.pure(true)
    } else {
      history.minimumOps.existsM {
         minOp =>
          for {
            p <- model.step(st, minOp.op)
            (newSt, expRet) = p
            opsResult <- history.ret(minOp)
            ans <- opsResult match {
              case Left(_) =>
                // if it failed, we dont know what it is, so
                // we try both
                (wingAndGongUnsafe(history.linearize(minOp), model, newSt), F.pure(false)).mapN(_ || _)
              case Right(actualRet) =>
                if (actualRet.result === expRet) {
                  wingAndGongUnsafe(history.linearize(minOp), model, newSt)
                } else {
                  F.pure(false)
                }

            }
          } yield ans
      }
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
  def analyse[F[_]: Parallel, Op, Res: Eq, St](history: List[Event[Op, Res]], model: Model[F, Op, Res, St], st: St)(
    implicit F: MonadError[F, Throwable]
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
