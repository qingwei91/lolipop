package raft.proptest

import cats.MonadError

trait History[F[_], Op, Re] {
  def minimumOps: List[Invoke[Op]]
  def ret(ops: Invoke[Op]): F[Ret[Re]]
  def linearize(ops: Invoke[Op]): History[F, Op, Re]
  def finished: Boolean
}

object History {
  def fromList[F[_], O, R](l: List[Event[O, R]])(implicit F: MonadError[F, Throwable]): History[F, O, R] = {
    lazy val perThread = l.groupBy(_.threadId)
    new History[F, O, R] {
      override def minimumOps: List[Invoke[O]] = {
        perThread.collect {
          case (_, (h: Invoke[O]) :: _) => h
        }.toList
      }

      override def ret(ops: Invoke[O]): F[Ret[R]] = {
        perThread(ops.threadId)
          .collectFirst {
            case r: Ret[R] => r
          }
          .liftTo[F](new IllegalArgumentException(s"No corresponding Ret event found for $ops"))
      }

      override def linearize(ops: Invoke[O]): History[F, O, R] = {
        val remaining = perThread
          .map {
            case (tid, events) if tid == ops.threadId =>
              events match {
                case o :: Ret(_, _) :: t if o == ops => t
              }
            case (_, other) => other
          }
          .flatten
          .toList
        fromList(remaining)
      }

      override def finished: Boolean = l.isEmpty
    }
  }
}
