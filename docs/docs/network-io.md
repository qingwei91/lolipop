---
layout: docs
title: Network IO
section: "usage"
---

### 3. Implement `NetworkIO`

`NetworkIO` represents the two kind of network communication in a Raft cluster, `AppendRequestRPC` and `VoteRequestRPC`.

User has to provide their own implementations, here's an example using http4s (Note: HTTP has relatively high overhead and thus is not recommended, this only serves as a reference)

```scala
import cats.effect.Sync
import io.circe.Encoder
import io.circe.generic.auto._
import org.http4s.Method._
import org.http4s._
import org.http4s.circe._
import org.http4s.client._
import org.http4s.client.dsl.Http4sClientDsl
import raft.algebra.io.NetworkIO
import raft.model._

class HttpNetwork[F[_]: Sync, Cmd: Encoder](networkConfig: Map[String, Uri], client: Client[F])
    extends NetworkIO[F, Cmd]
    with Http4sClientDsl[F]
    with CirceEntityEncoder
    with CirceEntityDecoder {
  override def sendAppendRequest(nodeID: String, appendReq: AppendRequest[Cmd]): F[AppendResponse] = {

    val uri = networkConfig(nodeID) / "append"

    client.expect[AppendResponse](POST.apply(body = appendReq, uri = uri))
  }

  override def sendVoteRequest(nodeID: String, voteRq: VoteRequest): F[VoteResponse] = {
    val uri = networkConfig(nodeID) / "vote"
    client.expect[VoteResponse](POST.apply(body = voteRq, uri = uri))
  }
}
```
