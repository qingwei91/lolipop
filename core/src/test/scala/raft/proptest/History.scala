package raft.proptest

import cats.data.Chain

trait History[Op, Re] {
  def minimumOps: List[Invoke[Op]]
  def ret(ops: Invoke[Op]): Either[Failed, Ret[Re]]
  def linearize(ops: Invoke[Op]): History[Op, Re]
  def skip(ops: Invoke[Op]): History[Op, Re]
  def finished: Boolean
  def linearized: Chain[Event[Op, Re]]
}

@SuppressWarnings(
  Array(
    "org.wartremover.warts.TraversableOps",
    "org.wartremover.warts.Product",
    "org.wartremover.warts.Serializable",
    "org.wartremover.warts.Throw"
  )
)
object History {
  case class ConcreteHistory[O, R](perThread: Map[String, List[Event[O, R]]], linearizedOps: Chain[Event[O, R]])
      extends History[O, R] {
    override def minimumOps: List[Invoke[O]] = {
      perThread.collect {
        case (_, (h: Invoke[O]) :: _) => h
      }.toList
    }

    override def ret(ops: Invoke[O]): Either[Failed, Ret[R]] = {
      perThread(ops.threadId)
        .collectFirst {
          case r: Ret[R] => Right(r)
          case f: Failed => Left(f)
        }
        .getOrElse(throw new IllegalArgumentException(s"No corresponding Ret event found for $ops"))
    }

    override def linearize(op: Invoke[O]): History[O, R] = {
      val (re, remaining) = dropOp(op)
      ConcreteHistory(remaining, linearizedOps.append(op).append(re))
    }

    override def finished: Boolean = perThread.forall(_._2.isEmpty)

    override def skip(op: Invoke[O]): History[O, R] = {
      val remaining = dropOp(op)

      ConcreteHistory(remaining._2, linearizedOps)
    }

    override def linearized: Chain[Event[O, R]] = linearizedOps

    private def dropOp(op: Invoke[O]): (Event[O, R], Map[String, List[Event[O, R]]]) = {
      val events = perThread(op.threadId)
      val first  = events.head
      val (droppedRet, updated) = if (first != op) {
        throw new IllegalArgumentException(s"Cannot drop $op because it is not the 1st op, 1st op is $first")
      } else {
        events(1) match {
          case r: Ret[R] => r -> events.drop(2)
          case f: Failed => f -> events.drop(2)
        }
      }
      droppedRet -> perThread.updated(op.threadId, updated)
    }
  }

  def fromList[O, R](l: List[Event[O, R]]): History[O, R] = {
    ConcreteHistory(l.groupBy(_.threadId), Chain.empty)
  }
}
