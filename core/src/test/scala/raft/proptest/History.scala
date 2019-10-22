package raft.proptest

import cats.MonadError
import cats.data.Chain

trait History[F[_], Op, Re] {
  def minimumOps: List[Invoke[Op]]
  def ret(ops: Invoke[Op]): F[Either[Failure, Ret[Re]]]
  def linearize(ops: Invoke[Op]): History[F, Op, Re]
  def skip(ops: Invoke[Op]): History[F, Op, Re]
  def finished: Boolean
  def linearized: Chain[Event[Op, Re]]
}

object History {
  @SuppressWarnings(
    Array(
      "org.wartremover.warts.TraversableOps",
      "org.wartremover.warts.Product",
      "org.wartremover.warts.Serializable",
      "org.wartremover.warts.Throw"
    )
  )
  def fromList[F[_], O, R](l: List[Event[O, R]])(implicit F: MonadError[F, Throwable]): History[F, O, R] = {

    def inner(perThread: Map[String, List[Event[O, R]]], linearizedOps: Chain[Event[O, R]]): History[F, O, R] = {
      new History[F, O, R] {
        override def minimumOps: List[Invoke[O]] = {
          perThread.collect {
            case (_, (h: Invoke[O]) :: _) => h
          }.toList
        }

        override def ret(ops: Invoke[O]): F[Either[Failure, Ret[R]]] = {
          perThread(ops.threadId)
            .collectFirst {
              case r: Ret[R] => Right(r)
              case f: Failure => Left(f)
            }
            .liftTo[F](new IllegalArgumentException(s"No corresponding Ret event found for $ops"))
        }

        override def linearize(op: Invoke[O]): History[F, O, R] = {
          val (re, remaining) = dropOp(op)
          inner(remaining, linearizedOps.append(op).append(re))
        }

        override def finished: Boolean = l.isEmpty

        override def skip(op: Invoke[O]): History[F, O, R] = {
          val remaining = dropOp(op)

          inner(remaining._2, linearizedOps)
        }

        override def linearized: Chain[Event[O, R]] = linearizedOps

        private def dropOp(op: Invoke[O]): (Event[O, R], Map[String, List[Event[O, R]]]) = {
          val events = perThread(op.threadId)
          val first  = events.head
          val (droppedRet, updated) = if (first != op) {
            throw new IllegalArgumentException(s"Cannot drop $op because it is not the 1st op, 1st op is $first")
          } else {
            events(1) match {
              case r: Ret[R] => r  -> events.drop(2)
              case f: Failure => f -> events.drop(2)
            }
          }
          droppedRet -> perThread.updated(op.threadId, updated)
        }
      }
    }
    inner(l.groupBy(_.threadId), Chain.empty)
  }
}
