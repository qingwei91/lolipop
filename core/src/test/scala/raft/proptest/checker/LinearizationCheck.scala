package raft
package proptest
package checker

import cats.{ Eq, Monad }
import cats.data.Chain
import cats.effect.ContextShift

sealed trait LinearizedRes[+A]
case class Linearizable[A](linearized: Chain[A]) extends LinearizedRes[A]
case class NonLinearizable[OP, Re](longestStreak: Chain[OP], failedOp: OP, expected: Re) extends LinearizedRes[OP]
case class LinearizationFailed[O, R](allFailures: List[NonLinearizable[O, R]]) extends LinearizedRes[O]

@SuppressWarnings(Array("org.wartremover.warts.All"))
object LinearizationCheck {

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

  // Ref: http://www.cs.ox.ac.uk/people/gavin.lowe/LinearizabiltyTesting/paper.pdf
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

}
