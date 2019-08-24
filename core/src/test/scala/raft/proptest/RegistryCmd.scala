package raft.proptest

sealed trait RegistryCmd
case class Put(k: String, v: String) extends RegistryCmd
