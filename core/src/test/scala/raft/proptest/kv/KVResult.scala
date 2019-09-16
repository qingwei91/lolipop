package raft
package proptest
package kv

sealed trait KVResult[+A]
case class ReadOK[A](a: Option[A]) extends KVResult[A]
case object WriteOK extends KVResult[Nothing]
