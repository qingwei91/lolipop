package raft.model

case class RaftLog[Cmd](idx: Int, term: Int, command: Cmd)
