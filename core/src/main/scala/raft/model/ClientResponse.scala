package raft
package model

sealed trait ClientResponse[+R]
case class CommandCommitted[Res](result: Res) extends ClientResponse[Res]
case class RedirectTo(nodeID: String) extends ClientResponse[Nothing]
case object NoLeader extends ClientResponse[Nothing]
