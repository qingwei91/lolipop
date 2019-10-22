package raft
package proptest
package checker

import cats.data.{ Chain, NonEmptyList }
import cats.{ Eq, MonadError, Parallel }

sealed trait LinearizedRes[+A]
case class Linearizable[A](linearized: Chain[A]) extends LinearizedRes[A]
case class NonLinearizable[A](longestStreak: Chain[A], failedOp: A, expected: A, actual: A) extends LinearizedRes[A]

object LinearizationCheck {

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
  ): F[LinearizedRes[Event[Op, Re]]] = {

    def tryLinearize(minOp: Invoke[Op], history: History[F, Op, Re], st: St): F[LinearizedRes[Event[Op, Re]]] = {
      for {
        p <- model.step(st, minOp.op)
        (newSt, expRet) = p
        opsResult <- history.ret(minOp)
        ans <- opsResult match {
                case Left(_) =>
                  val assumeLinearized = loop(history.linearize(minOp), newSt)

                  val assumeFailed = loop(history.skip(minOp), st)

                  (
                    assumeLinearized,
                    assumeFailed
                  ).mapN[LinearizedRes[Event[Op, Re]]] {
                    case (lin: Linearizable[Event[Op, Re]], _) =>
                      lin
                    case (_, notLin: Linearizable[Event[Op, Re]]) =>
                      notLin
                    case (lin: NonLinearizable[Event[Op, Re]], notLin: NonLinearizable[Event[Op, Re]]) =>
                      if (lin.longestStreak.size > notLin.longestStreak.size) {
                        lin
                      } else {
                        notLin
                      }
                  }

                case Right(actualRet) =>
                  if (actualRet.result === expRet) {
                    val linearizedHs = history.linearize(minOp)
                    loop(linearizedHs, newSt)
                  } else {

                    NonLinearizable(history.linearized, minOp, actualRet.copy(result = expRet), actualRet)
                      .pure[F]
                      .widen[LinearizedRes[Event[Op, Re]]]
                  }
              }
      } yield ans

    }

    def loop(history: History[F, Op, Re], st: St): F[LinearizedRes[Event[Op, Re]]] = {
      if (history.finished) {
        Linearizable(history.linearized).pure[F].widen
      } else {

        NonEmptyList
          .fromListUnsafe(history.minimumOps)
          .reduceLeftM(minOp => tryLinearize(minOp, history, st)) {
            case (lin @ Linearizable(_), _) => lin.pure[F].widen
            case (prevFailure @ NonLinearizable(longestSoFar, _, _, _), minOp) =>
              tryLinearize(minOp, history, st).map {
                case curFailure @ NonLinearizable(newLongest, _, _, _) =>
                  if (longestSoFar.size > newLongest.size) {
                    prevFailure
                  } else {
                    curFailure
                  }
                case other => other
              }
          }
      }
    }
    loop(history, st)
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
