package raft
package model

sealed trait ClientResponse
case object CommandCommitted          extends ClientResponse
case class RedirectTo(nodeID: String) extends ClientResponse
case object NoLeader                  extends ClientResponse
