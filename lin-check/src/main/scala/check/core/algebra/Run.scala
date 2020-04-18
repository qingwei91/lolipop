package check.core.algebra

import java.util.UUID

import cats.Parallel
import cats.effect.{ Concurrent, Timer }
import cats.effect.syntax.all._
import cats.syntax.all._
import check.core.model._

import scala.concurrent.TimeoutException
import scala.concurrent.duration.FiniteDuration

object Run {
  def apply[F[_]: Timer: Concurrent: Parallel, Req, Res](
    remoteStateMachine: StateMachine[F, Req, Res],
    sequentialStateMachine: LocalStateMachine[F, Req, Res],
    checker: LinCheck[F, Req, Res]
  )(operationPlan: Map[String, List[Req]], timeout: FiniteDuration): F[LinearizationResult[Req, Res]] = {

    val history = fs2.Stream
      .emits[F, fs2.Stream[F, DistributedEvent[Req, Res]]](
        operationPlan.map {
          case (key, reqs) =>
            fs2.Stream.emits(reqs).evalMap { req =>
              val opId = UUID.randomUUID().toString
              remoteStateMachine
                .execute(req)
                .timeout(timeout)
                .map[DistributedEvent[Req, Res]] { res =>
                  Completed(opId, concurrentId = key, req = req, res = res)
                }
                .recover {
                  case _: TimeoutException => Timeout(opId, key, req)
                }
            }
        }.toList
      )
      .parJoin(operationPlan.size)
    checker.check(history, sequentialStateMachine)
  }
}
