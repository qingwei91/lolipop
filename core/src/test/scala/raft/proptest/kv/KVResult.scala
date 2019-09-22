package raft
package proptest
package kv

import cats.Show

sealed trait KVResult[+A]
case class ReadOK[A](a: Option[A]) extends KVResult[A]
case object WriteOK extends KVResult[Nothing]

object KVResult {
  implicit def showResult[A]: Show[KVResult[A]] = Show.show {
    case ReadOK(Some(a)) => s"Read $a"
    case ReadOK(None) => "Read None"
    case WriteOK => "Written"
  }
}
