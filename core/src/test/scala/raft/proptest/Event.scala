package raft.proptest

sealed trait Event[+O, +R] {
  def threadId: String
}
case class Invoke[Op](threadId: String, op: Op) extends Event[Op, Nothing]
case class Ret[R](threadId: String, result: R) extends Event[Nothing, R]
case class Failure(threadId: String, err: Throwable) extends Event[Nothing, Nothing]
