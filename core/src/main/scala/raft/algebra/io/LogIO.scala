package raft.algebra.io

import raft.model.RaftLog

trait LogIO[F[_], Cmd] {
  type Log = RaftLog[Cmd]
  def getByIdx(idx: Int): F[Option[Log]]
  def overwrite(logs: Seq[Log]): F[Unit]
  def lastLog: F[Option[Log]]

  /**
    * @param idx - inclusive
    */
  def takeFrom(idx: Int): F[Seq[Log]]
  def append(log: Log): F[Unit]
}
