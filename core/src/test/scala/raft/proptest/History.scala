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
    ListHistory(l.sortBy(_.startTime), Chain.empty)
  }
}

trait History[Op, Re] {
  type FO = FullOperation[Op, Re]
  def minimumOps: List[FO]
  def linearize(ops: FO): History[Op, Re]
  def skip(ops: FO): History[Op, Re]
  def finished: Boolean
  def linearized: Chain[FO]
}

/**
  * @param remaining remain Ops sorted by startTime
  *                  Potential perf boost by using treeMap, where each
  *                  key map to all concurrent ops, then remove is
  *                  O(nlogn) instead of O(n) and constructing the
  *                  whole map is probably O(n^2) for once?
  */
case class ListHistory[O, R](remaining: List[FullOperation[O, R]], _linearized: Chain[FullOperation[O, R]])
    extends History[O, R] {
  override def minimumOps: List[FO] = {
    remaining match {
      case oldestStart :: rest =>
        //TODO potential perf boost by not allocating
        val overlapped = rest.filter {
          case FullOperation(tid, _, _, start, _) =>
            start <= oldestStart.endTime && tid != oldestStart.threadId
        }
        oldestStart :: overlapped
      case Nil => Nil
    }
  }

  override def linearize(ops: FO): History[O, R] = {
    ListHistory(remaining.filterNot(_ == ops), _linearized.append(ops))
  }

  override def skip(ops: FO): History[O, R] = ListHistory(remaining.filterNot(_ == ops), _linearized)

  override def finished: Boolean = remaining == Nil

  override def linearized: Chain[FO] = _linearized
}
