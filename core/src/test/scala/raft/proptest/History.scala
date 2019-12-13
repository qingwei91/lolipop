package raft.proptest

import cats.data.Chain
@SuppressWarnings(
  Array(
    "org.wartremover.warts.TraversableOps",
    "org.wartremover.warts.Product",
    "org.wartremover.warts.Serializable",
    "org.wartremover.warts.Throw"
  )
)
object History {

  def fromList[O, R](l: List[FullOperation[O, R]]): History[O, R] = {
    ListHistory(l.sortBy(_.startTime), Chain.empty, Nil)
  }
}

trait History[Op, Re] {
  type FO = FullOperation[Op, Re]
  def minimumOps: List[FO]
  def linearize(ops: FO): History[Op, Re]
  def skip(ops: FO): History[Op, Re]
  def finished: Boolean
  def linearized: Chain[FO]
  def trackError(failedOp: FO): History[Op, Re]
}

/**
  * @param remaining remain Ops sorted by startTime
  *                  Potential perf boost by using treeMap, where each
  *                  key map to all concurrent ops, then remove is
  *                  O(nlogn) instead of O(n) and constructing the
  *                  whole map is probably O(n^2) for once?
  */
case class ListHistory[O, R](
  remaining: List[FullOperation[O, R]],
  _linearized: Chain[FullOperation[O, R]],
  failedOps: List[FullOperation[O, R]]
) extends History[O, R] {
  override def minimumOps: List[FO] = {
    /*
    fixme:
    logic is wrong as
    1. it assumes operation within the same thread can never be concurrent
    this is false in case of timeout
    2. it always use the old op as starting point, but this is a bad starting point if
    it timed out, as 2 event can be concurrent with it, but not necessarily concurrent between them
    eg. if A timed out, and C and B didn't then using A as starting point will include B and C as
    concurrent op, but B and C might have causal relationship between them
     */

    val nonFailures = remaining match {
      case oldestStart :: rest =>
        val overlapped = rest.filter {
          case FullOperation(tid, _, _, start, _) =>
            !start.isAfter(oldestStart.endTime) && tid != oldestStart.threadId
        }
        oldestStart :: overlapped
      case Nil => Nil
    }
    nonFailures ::: failedOps
  }

  override def linearize(ops: FO): History[O, R] = {
    ListHistory(remaining.filterNot(_ == ops), _linearized.append(ops), failedOps.filterNot(_ == ops))
  }

  override def skip(ops: FO): History[O, R] = ListHistory(remaining.filterNot(_ == ops), _linearized, failedOps)

  override def finished: Boolean = remaining == Nil

  override def linearized: Chain[FO] = _linearized
  override def trackError(failedOp: FullOperation[O, R]): History[O, R] = {
    ListHistory(remaining.filterNot(_ == failedOp), _linearized, failedOp :: failedOps)
  }
}
