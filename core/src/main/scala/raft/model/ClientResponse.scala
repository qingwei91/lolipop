package raft
package model

sealed trait ClientResponse

sealed trait WriteResponse[+R] extends ClientResponse
sealed trait ReadResponse[+S] extends ClientResponse

case class CommandCommitted[Res](result: Res) extends WriteResponse[Res]
case class RedirectTo(nodeID: String) extends WriteResponse[Nothing] with ReadResponse[Nothing]

case object NoLeader extends WriteResponse[Nothing] with ReadResponse[Nothing]
case class Query[Result](result: Result) extends ReadResponse[Result]
