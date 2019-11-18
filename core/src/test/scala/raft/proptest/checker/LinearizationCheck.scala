package raft
package proptest
package checker

import cats.data.{ Chain, NonEmptyList }
import cats.effect.Concurrent
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
    *
    * Ref: http://www.cs.ox.ac.uk/people/gavin.lowe/LinearizabiltyTesting/paper.pdf
    */
  def wingAndGongUnsafe[F[_], Op, Re: Eq, St](
    history: History[Op, Re],
    model: Model[Op, Re, St],
    st: St
  )(implicit F: Concurrent[F], P: Parallel[F]): F[LinearizedRes[Event[Op, Re]]] = {

    def tryLinearize(
      minOp: Invoke[Op],
      history: History[Op, Re],
      st: St
    ): F[LinearizedRes[Event[Op, Re]]] = {
      val (newSt, expRet) = model.step(st, minOp.op)
      val opsResult       = history.ret(minOp)
      opsResult match {
        case Left(_) =>
          val assumeLinearized = loop(history.linearize(minOp), newSt)
          val assumeFailed     = loop(history.skip(minOp), st)

          (assumeLinearized, assumeFailed).parMapN {
            case (lin: Linearizable[Event[Op, Re]], _) => lin
            case (_, notLin: Linearizable[Event[Op, Re]]) => notLin
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
            NonLinearizable(history.linearized, minOp, actualRet.copy(result = expRet), actualRet).pure[F].widen
          }
      }
    }

    def loop(history: History[Op, Re], st: St): F[LinearizedRes[Event[Op, Re]]] = {
      if (history.finished) {
        Linearizable(history.linearized).pure[F].widen
      } else {
        val result = NonEmptyList
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
        result
      }
    }

    loop(history, st)
  }

  @SuppressWarnings(
    Array(
      "org.wartremover.warts.All"
    )
  )
  def wingAndGongUnsafeNew[Op, Re: Eq, St](
    history: History[Op, Re],
    model: Model[Op, Re, St],
    st: St
  ): LinearizedRes[Event[Op, Re]] = {

    def tryLinearize(
      minOp: Invoke[Op],
      history: History[Op, Re],
      st: St
    ): LinearizedRes[Event[Op, Re]] = {
      val (newSt, expRet) = model.step(st, minOp.op)
      val opsResult       = history.ret(minOp)
      opsResult match {
        case Left(_) =>
          val assumeLinearized = loop(history.linearize(minOp), newSt)
          val assumeFailed     = loop(history.skip(minOp), st)

          (assumeLinearized, assumeFailed) match {
            case (lin: Linearizable[Event[Op, Re]], _) => lin
            case (_, notLin: Linearizable[Event[Op, Re]]) => notLin
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
          }
      }
    }

    def loop(history: History[Op, Re], st: St): LinearizedRes[Event[Op, Re]] = {
      if (history.finished) {
        Linearizable(history.linearized)
      } else {
        /*
        1. take 1 from minOp
        2. try to linearize minOp
        3. If success, continue linearize the rest, go to 1
        4. If failed, jump to next minOp
        5. If no more minOp, return the best try
         */

        var foundResult                          = false
        var cur                                  = 0
        var result: LinearizedRes[Event[Op, Re]] = null

        while (!foundResult && cur != history.minimumOps.size) {
          val currMinOp = history.minimumOps(cur)
          cur = cur + 1
          val (newSt, expRet) = model.step(st, currMinOp.op)
          val actualResult    = history.ret(currMinOp)
          actualResult match {
            case Left(_) =>
              val assumeLinearized = loop(history.linearize(minOp), newSt)
              val assumeFailed     = loop(history.skip(minOp), st)

              result = (assumeLinearized, assumeFailed) match {
                case (lin @ Linearizable(_), _) => lin
                case (_, notLin @ Linearizable(_)) => notLin
                case (lin: NonLinearizable[Event[Op, Re]], notLin: NonLinearizable[Event[Op, Re]]) =>
                  if (lin.longestStreak.size > notLin.longestStreak.size) {
                    lin
                  } else {
                    notLin
                  }

                case Right(succeeded) =>
              if (succeeded.result === expRet) {
                val linearizedHs = history.linearize(currMinOp)
                loop(linearizedHs, newSt) match {
                  case lin @ Linearizable(_) =>
                    result      = lin
                    foundResult = true
                  case non @ NonLinearizable(newLongest, _, _, _) =>
                    if (result == null) {
                      result = non
                    } else {
                      val longestSoFar = result.asInstanceOf[NonLinearizable[Event[Op, Re]]].longestStreak

                      result = if (longestSoFar.size > newLongest.size) {
                        result
                      } else {
                        non
                      }
                    }
                }
              } else {
                result = NonLinearizable(history.linearized, currMinOp, succeeded.copy(result = expRet), succeeded)
              }
          }
        }
        result

//            val result = history.minimumOps match {
//              case minOp :: tail =>
//                tail.foldLeft(tryLinearize(minOp, history, st)) {
//                  case (lin @ Linearizable(_), _) => lin
//                  case (prevFailure @ NonLinearizable(longestSoFar, _, _, _), nextMinOp) =>
//                    println(s"Failed to linearize 1st MinOP of ${tail}, ${history.linearized.size} ...........")
//                    tryLinearize(nextMinOp, history, st) match {
//                      case curFailure @ NonLinearizable(newLongest, _, _, _) =>
//                        if (longestSoFar.size > newLongest.size) {
//                          prevFailure
//                        } else {
//                          curFailure
//                        }
//                      case other => other
//                    }
//                }
//            }
      }
    }

    loop(history, st)
  }

}
