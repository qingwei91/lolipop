package raft
package proptest
package checker

import cats.Eq
import cats.data.Chain
import cats.effect.{ Concurrent, ContextShift, IO, Sync }
import cats.Monad

import scala.tools.cmd.Opt
import scala.annotation.tailrec

sealed trait LinearizedRes[+A]
case class Linearizable[A](linearized: Chain[A]) extends LinearizedRes[A]
case class NonLinearizable[OP, Re](longestStreak: Chain[OP], failedOp: OP, expected: Re) extends LinearizedRes[OP]
case class LinearizationFailed[O, R](allFailures: List[NonLinearizable[O, R]]) extends LinearizedRes[O]

@SuppressWarnings(Array("org.wartremover.warts.All"))
object LinearizationCheck {

  /**
    * This only works if F's flatmap is cancellable, this is true for cats-effect
    */
  def cancellableLoop[F[_], LCtx, A](
    step: LCtx => Either[LCtx, A]
  )(init: LCtx)(implicit cs: ContextShift[F], monad: Monad[F]): F[A] = {

    def inner(in: LCtx, i: Int): F[A] = {
      if (i > 2000) {
        cs.shift.flatMap(_ => inner(in, 0))
      } else {
        step(in) match {
          case Left(cont) => inner(cont, i + 1)
          case Right(a) => a.pure[F]
        }
      }
    }
    inner(init, 0)
  }

  /**
    * LoopCtx models a stack where prevCtx gives access to caller's context and
    * hist,state,idx are params
    * lastFailure is used to accumulate errors
    */
  case class LoopCtx[O, R, S](
    prevCtx: Option[LoopCtx[O, R, S]],
    hist: History[O, R],
    state: S,
    idx: Int,
    lastFailures: List[NonLinearizable[FullOperation[O, R], R]]
  )

  def wAndG[F[_]: Monad: ContextShift, Op, Re: Eq, St](
    history: History[Op, Re],
    model: Model[Op, Re, St],
    st: St
  ): F[LinearizedRes[FullOperation[Op, Re]]] = {

    val init = LoopCtx(None, history, st, 0, Nil)

    def step(ctx: LoopCtx[Op, Re, St]): Either[LoopCtx[Op, Re, St], LinearizedRes[FullOperation[Op, Re]]] = {
      import ctx._
      if (hist.finished) {
        Right(Linearizable(hist.linearized))
      } else {
        hist.minimumOps.get(idx) match {
          case None =>
            // ran out of options, bailed
            // .get is safe assuming history is not empty
            prevCtx match {
              case None => Right(LinearizationFailed(lastFailures))
              case Some(lastCtx) => Left(lastCtx.copy(idx = lastCtx.idx + 1))
            }

          case Some(currOp) =>
            val (nextState, expRe) = model.step(state, currOp.op)
            currOp.ret match {
              case Left(err) =>
                /*
                if op fail in actual setup, we can assume it completes and proceed, as failed
                op span from start time to infinite time, meaning we can keep trying and eventually
                we should find an instant where it linearized or never
                 */

                Left(LoopCtx(prevCtx, hist.trackError(currOp), state, 0, lastFailures))
              case Right(actualRet) if actualRet === expRe =>
                Left(LoopCtx(ctx.some, hist.linearize(currOp), nextState, 0, lastFailures))
              case Right(actualRet) if actualRet =!= expRe =>
                val lastErr = NonLinearizable(hist.linearized, currOp, expRe)
                Left(LoopCtx(prevCtx, hist, state, idx + 1, lastErr :: lastFailures))
            }
        }
      }
    }
    cancellableLoop[F, LoopCtx[Op, Re, St], LinearizedRes[FullOperation[Op, Re]]](step)(init)
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
    *
    * Ref: http://www.cs.ox.ac.uk/people/gavin.lowe/LinearizabiltyTesting/paper.pdf
    */
  val MODEL_CHECK_FAIL    = 0
  val MODEL_CHECK_MATCH   = 1
  val MODEL_CHECK_UNMATCH = 2

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
              /* assume it completed and proceed
              If it didn't complete, it will failed at some point, then we will
              backtrack to next concurrent op and try that
               */
              lastResult = loop(currHist.linearize(fo), st)
              lastResult match {
                case Linearizable(_) => foundSolution          = true
                case NonLinearizable(_, _, _) => foundSolution = false
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
