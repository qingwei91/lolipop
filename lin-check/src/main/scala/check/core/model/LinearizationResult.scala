package check.core.model

sealed trait LinearizationResult[Req, Res] {
  def isSuccess: Boolean
}

case class Linearizable[Req, Res](validHistory: List[DistributedEvent[Req, Res]])
    extends LinearizationResult[Req, Res] {
  override def isSuccess: Boolean = true
}
case class NotLinearizable[Req, Res](
  closestFailedHistory: List[DistributedEvent[Req, Res]],
  historyThatFailed: List[DistributedEvent[Req, Res]],
  expectedHistory: List[DistributedEvent[Req, Res]]
) extends LinearizationResult[Req, Res] {
  override def isSuccess: Boolean = false
}
