package raft.http

import org.http4s.Uri

case class NetworkConfig(
  nodeIDToURI: Map[String, Uri]
)
