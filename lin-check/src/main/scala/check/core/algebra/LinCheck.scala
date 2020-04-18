package check.core.algebra

import check.core.model.{ DistributedEvent, LinearizationResult }
import fs2.Stream

trait LinCheck[F[_], Req, Res] {
  def check(
    history: Stream[F, DistributedEvent[Req, Res]],
    sequentialObj: LocalStateMachine[F, Req, Res]
  ): F[LinearizationResult[Req, Res]]
}
