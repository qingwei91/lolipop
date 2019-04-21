package raft
package model

case class RaftLog[Cmd](idx: Int, term: Int, command: Cmd)
