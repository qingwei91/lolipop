package raft
package setup

import java.io.{ File, PrintWriter }

import cats.Monad
import raft.algebra.event.InMemEventLogger
import raft.model.RaftNodeState

// A class to hold things needed in test cases
case class RaftTestComponents[F[_]](
  proc: RaftProcess[F, String, String],
  state: RaftNodeState[F, String],
  eventLogger: InMemEventLogger[F, String, String]
) {
  def api: RaftApi[F, String, String] = proc.api

  def flushLogTo(file: File)(implicit M: Monad[F]): F[Unit] = {
    for {
      strBuf <- eventLogger.logs.get
    } yield {
      val pw = new PrintWriter(file)
      pw.println(strBuf.toString)
      pw.close()
    }
  }
}
