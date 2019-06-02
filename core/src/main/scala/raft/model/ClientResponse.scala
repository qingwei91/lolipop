package raft
package model

sealed trait ClientResponse

sealed trait WriteResponse extends ClientResponse
sealed trait ReadResponse[+S] extends ClientResponse

case object CommandCommitted extends WriteResponse
case class RedirectTo(nodeID: String) extends WriteResponse with ReadResponse[Nothing]

case object NoLeader extends WriteResponse with ReadResponse[Nothing]
case class Read[State](state: State) extends ReadResponse[State]
