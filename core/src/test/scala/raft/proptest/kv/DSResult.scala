package raft.proptest.kv

sealed trait DSResult[+A]
case class ReadOK[A](a: A) extends DSResult[A]
case object WriteOK extends DSResult[Nothing]
case class Failed(t: Throwable) extends DSResult[Nothing]
case object Timeout extends DSResult[Nothing]

sealed trait Event[O, R] {
  def threadId: String
}
case class Invoke[Op, R](threadId: String, op: Op) extends Event[Op, R]
case class Ret[O, R](threadId: String, result: DSResult[R]) extends Event[O, R]

