package check
package wag

import cats.data.Chain
import cats.effect.Sync
import cats.syntax.all._
import check.core.algebra.{ LinCheck, LocalStateMachine }
import check.core.model._

class WingAngGongChecker[F[_]: Sync, Req, Res] extends LinCheck[F, Req, Res] {
  type Ev  = DistributedEvent[Req, Res]
  type StM = LocalStateMachine[F, Req, Res]
  override def check(
    history: fs2.Stream[F, DistributedEvent[Req, Res]],
    sequentialObj: LocalStateMachine[F, Req, Res]
  ): F[LinearizationResult[Req, Res]] = {
    history.compile.toList.map { allEvents =>
      findPath(new History(allEvents), 0, Chain.empty, sequentialObj)
    }
  }

  def findPath(remaining: History, pathToTake: Int, validPath: Chain[Ev], state: StM): LinearizationResult[Req, Res] = {
    val minOps = remaining.concurrentOps
    minOps match {
      case Nil => Linearizable(validPath.toList)
      case _ =>
        val opToTry = minOps(pathToTake)
        opToTry match {
          case ev @ Timeout(operationId, _, req) =>
            val (_, nextSt) = state.execute(req)
            val pathIfOpExecuted = findPath(
              remaining.dropOne(operationId),
              0,
              validPath.append(ev),
              nextSt
            )
            if (pathIfOpExecuted.isSuccess) {
              pathIfOpExecuted
            } else {
              val pathIfOpFailed = findPath(remaining.dropOne(operationId), 0, validPath, state)
              if (pathIfOpFailed.isSuccess) {
                pathIfOpFailed
              } else {
                // if delayed, the event will live in concurrentOps until it's been linearized
                // History abstraction need to know that timeout means it breaks sequentiality from the events order
                val pathIfOpDelayed = findPath(remaining, pathToTake + 1, validPath, state)
                pathIfOpDelayed
              }
            }

          case ev @ Completed(operationId, _, req, res) =>
            val (expectedRes, nextSt) = state.execute(req)
            if (expectedRes == res) {
              findPath(remaining.dropOne(operationId), 0, validPath.append(ev), nextSt)
            } else {
              val nextPath = pathToTake + 1
              if (minOps.isDefinedAt(nextPath)) {
                findPath(remaining, nextPath, validPath, state)
              } else {
                NotLinearizable(
                  validPath.toList,
                  validPath.append(ev).toList,
                  validPath.append(ev.copy(res = expectedRes)).toList
                )
              }
            }
        }
    }
  }

  class History(rawEvents: List[Ev]) {
    def dropOne(id: String): History          = ???
    def concurrentOps: List[Ev]               = ???
    def registerConcurrentOp(ev: Ev): History = ???
  }

}
