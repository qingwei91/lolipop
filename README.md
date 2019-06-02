# Lolipop - purely functional Raft implementation

**Lolipop** is a basic implementation of Raft algorithm using a purely functional approach. It is effect agnostic meaning user can choose your own effect type (eg. cats IO, Future, ZIO)

It does not support

* Dynamic membership
* Log compaction 


## Usage

**Lolipop** is rather minimal, thus user need to provide a few dependencies and deal with the wiring.

### 1. Define `StateMachine`

StateMachine is application specific, it represents the State that we are trying to reach consensus for. It could be as simple as an Int, or it could be a collection of documents. 

```scala
import cats.effect._
import cats.implicits._

val counter: IO[StateMachine[IO, ChangeCount, Int]] = for {
  state <- Ref[IO].of(0)
} yield {
  new StateMachine[IO, ChangeCount, Int] {
    override def execute(cmd: ChangeCount): IO[Int] = {
      state.modify { i =>
        val j = i + cmd.i
        j -> j
      }
    }
  }
}
```

### 2. Define `LogIO`

`LogIO` represent the ability for the application to interact with log files, **Lolipop** allow user to implement this because there are different ways to persist logs (eg. file system, database) 

Below is an example implemented using [SwayDB](http://www.swaydb.io/)

```scala
import cats.effect.IO
import raft.algebra.io.LogIO
import raft.model.RaftLog

class FileLogIO(db: swaydb.Map[Int, RaftLog[ChangeCount], IO]) extends LogIO[IO, ChangeCount] {

  override def getByIdx(idx: Int): IO[Option[Log]] = db.get(idx)

  override def overwrite(logs: Seq[Log]): IO[Unit] = {
    db.put(logs.map(s => s.idx -> s)).map(_ => ())
  }

  override def lastLog: IO[Option[Log]] = db.lastOption.map { opt =>
    opt.map(_._2)
  }

  /**
    * @param idx - inclusive
    */
  override def takeFrom(idx: Int): IO[Seq[Log]] = {
    db.fromOrAfter(idx).takeWhile(_ => true).map(_._2).materialize
  }

  override def append(log: Log): IO[Unit] = db.put(log.idx, log).map(_ => ())
}
```

### 3. Define `NetworkIO`

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

### 4. Define `EventLogger`

EventLogger is useful for debugging, it is being used to record important events in a Raft process. There is a `Slf4jEventLogger` provided out of the box.

### 5. Create `RaftProcess

Once we have the above dependencies, we can build a RaftProcess

```
import raft._

val stateMachine = new StateMachine { .... }

// config that represent cluster membership, so that a node knows about its peer node
val myId = "node0"
val clusterConfig = ClusterConfig(myId, Set("node1", "node2"))

val logIO = new FileLogIO(...)
val networkIO = new HttpNetwork(...)
val eventLogger = new Slf4jEventLogger(myId)

val proc = RaftProcess.simple(
  stateMachine,
  clusterConfig,
  logIO,
  networkIO,
  eventLogger
)

```

### 6. Expose raft api to the network

`RaftProcess` provides several abilities 

1. Start the Raft Server, modelled by `def startRaft: Stream[F, Unit]`
2. Receive AppendLog Request from peer nodes
3. Receive Vote Request from peer nodes
3. Receive Command from clients

* `Peer node` refers to the node in the same Raft cluster.
* `Client` refers to process that uses Raft algorithm to store state.
* All api is grouped under `RaftApi` which is a field on `RaftProcess`, `def api: RaftApi[F, Cmd]` 

We should expose the api to the network before we start the Raft Process, the integration depends on the networking protocol and library.

Below is an example by using HTTP4S

```scala
    def raftProtocol(api: RaftApi[F, Cmd]): HttpRoutes[F] =
      HttpRoutes
        .of[F] {
          case req @ POST -> Root / "append" =>
            for {
              app   <- req.as[AppendRequest[Cmd]]
              reply <- api.requestAppend(app)
              res   <- Ok(reply.asJson)
            } yield res

          case req @ POST -> Root / "vote" =>
            for {
              vote  <- req.as[VoteRequest]
              reply <- api.requestVote(vote)
              res   <- Ok(reply.asJson)
            } yield res
          case req @ POST -> Root / "cmd" =>
            for {
              change <- req.as[Cmd]
              reply  <- api.incoming(change)
              res    <- Ok(reply.asJson)
            } yield res
        }
        
    val server = BlazeServerBuilder[F]
        .bindHttp(9000,"localhost")
        // This is how you hook api to HTTP4S
        .withHttpApp(raftProtocol(raftProc.api).orNotFound) 
        .serve
```

### 7. Start the RaftProcess

RaftProcess is a logical long running process, it has 2 responsibilities:

1. Execute client's request, eg. WriteCommand or ReadState
2. Periodically check the health of peer nodes

It is modelled as a `fs2.Stream`, it is typically being invoked in your Main class.


