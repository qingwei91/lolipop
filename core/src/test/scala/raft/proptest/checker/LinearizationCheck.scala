package raft
package proptest
package checker

import cats.Eq
import cats.data.Chain

sealed trait LinearizedRes[+A]
case class Linearizable[A](linearized: Chain[A]) extends LinearizedRes[A]
case class NonLinearizable[OP, Re](longestStreak: Chain[OP], failedOp: OP, expected: Re) extends LinearizedRes[OP]

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
  val MODEL_CHECK_FAIL    = 0
  val MODEL_CHECK_MATCH   = 1
  val MODEL_CHECK_UNMATCH = 2

  @SuppressWarnings(Array("org.wartremover.warts.All"))
  def betterWingAndGong[Op, Re: Eq, St](
    history: History[Op, Re],
    model: Model[Op, Re, St],
    st: St
  ): LinearizedRes[FullOperation[Op, Re]] = {
    type FO = FullOperation[Op, Re]
    def attemptSerialize(fo: FO, st: St): (Int, St, Re) = {
      val (nextState, expRe) = model.step(st, fo.op)
      fo.ret match {
        case Left(_) =>
          (MODEL_CHECK_FAIL, nextState, expRe)
        // failure in real life, op might gone through, or might not
        // try both path, would parallelization help?
        case Right(actRe) =>
          if (actRe === expRe) {
            (MODEL_CHECK_MATCH, nextState, expRe)
          } else {
            (MODEL_CHECK_UNMATCH, nextState, expRe)
          }
      }
    }

    def loop(currHist: History[Op, Re], currSt: St): LinearizedRes[FO] = {
      if (currHist.finished) {
        Linearizable(currHist.linearized)
      } else {
        val concurrentOps = currHist.minimumOps
        var i             = 0

        var lastResult: LinearizedRes[FO] = null
        var foundSolution: Boolean        = false
        while (i < concurrentOps.size && !foundSolution) {
          val fo = concurrentOps(i)
          i = i + 1
          val attempt = attemptSerialize(fo, currSt)
          attempt match {
            case (MODEL_CHECK_FAIL, st, _) =>
              val assumeExecuted = loop(currHist.linearize(fo), st)

              lastResult = assumeExecuted match {
                case lin @ Linearizable(_) =>
                  foundSolution = true
                  lin
                case unmatchA @ NonLinearizable(_, _, _) =>
                  loop(currHist.skip(fo), st) match {
                    case lin @ Linearizable(_) =>
                      foundSolution = true
                      lin
                    case unmatchB @ NonLinearizable(_, _, _) =>
                      if (unmatchA.longestStreak.size > unmatchB.longestStreak.size) {
                        unmatchA
                      } else {
                        unmatchB
                      }

                  }
              }

            case (MODEL_CHECK_UNMATCH, _, expRe) =>
              val curUnmatch = NonLinearizable(currHist.linearized, fo, expRe)
              lastResult = if (lastResult == null) {
                curUnmatch
              } else {
                lastResult match {
                  case NonLinearizable(longestStreak, _, _) =>
                    if (longestStreak.size > curUnmatch.longestStreak.size) {
                      lastResult
                    } else {
                      curUnmatch
                    }
                  case _ => throw new IllegalStateException("Unexpected lastResult is Linearizable")
                }
              }
            case (MODEL_CHECK_MATCH, st, _) =>
              lastResult = loop(currHist.linearize(fo), st)
              lastResult match {
                case Linearizable(_) => foundSolution          = true
                case NonLinearizable(_, _, _) => foundSolution = false
              }
          }
        }
        lastResult
      }
    }
    loop(history, st)
  }
}
