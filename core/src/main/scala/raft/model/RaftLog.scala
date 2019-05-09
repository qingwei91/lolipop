package raft
package model

import cats.Show

case class RaftLog[Cmd](idx: Int, term: Int, command: Cmd)
object RaftLog {
  implicit def showLog[Cmd: Show]: Show[RaftLog[Cmd]] = new Show[RaftLog[Cmd]] {
    override def show(t: RaftLog[Cmd]): String = {
      s"(Idx=${t.idx}, term=${t.term}, command=${t.command}"
    }
  }
}
