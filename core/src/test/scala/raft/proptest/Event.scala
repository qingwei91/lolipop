package raft
package proptest

import cats.Show

sealed trait Event[+O, +R] {
  def threadId: String
}
case class Invoke[Op](threadId: String, op: Op) extends Event[Op, Nothing]
case class Ret[R](threadId: String, result: R) extends Event[Nothing, R]

// explicitly model Failure in event, because Event is a polymorphic
// type that can be handled by checker without the knowledge of
// the actual API protocol
// by having Failure in Event, checker can handle Failure
// explicitly and figure out how to handle it
case class Failure(threadId: String, err: Throwable) extends Event[Nothing, Nothing]

object Event {
  implicit def showEvent[I: Show, O: Show]: Show[Event[I, O]] = Show.show {
    case Invoke(threadId, op) => s"Thread $threadId - Invoke ${op.show}"
    case Ret(threadId, result) => s"Thread $threadId - Return ${result.show}"
    case Failure(threadId, err) => s"Thread $threadId - Failed with ${err.getMessage}"
  }
}
