package check.core.model

sealed trait DistributedEvent[+Req, +Res] {
  def operationId: String
  def concurrentId: String
}

case class Timeout[A](operationId: String, concurrentId: String, req: A) extends DistributedEvent[A, Nothing]
case class Completed[A, B](operationId: String, concurrentId: String, req: A, res: B) extends DistributedEvent[A, B]
