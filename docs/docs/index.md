---
layout: docs
title: Usage
---

# Lolipop - purely functional Raft implementation

**Lolipop** is a basic implementation of Raft algorithm using a purely functional approach. It is effect agnostic meaning user can choose your own effect type (eg. cats IO, Future, ZIO)

It does not support the following feature (todo)

* Dynamic membership
* Log compaction 


## Usage

**Lolipop** is rather minimal, thus user need to provide a few dependencies and deal with the wiring.

### 1. Define `StateMachine`

Raft algorithm is used to implement a replicated state machine, represented by StateMachine in our code.

StateMachine is application specific, it manages the State that we are trying to reach consensus for. It could be as simple as an Int, or it could be a collection of documents. 


Below is a sample implementation, there are 2 method to implement
```scala
import cats.effect._
import cats.implicits._
import raft.algebra.StateMachine

case class ChangeCount(i: Int)

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
    override def getCurrent: IO[Int] = state.get
  }
}
```

### 2. Define `LogIO`

Raft algorithm reach consensus by replicating logs in the cluster, and logs are expected to be durable so that consensus are maintain in spite of server crash.

`LogIO` represent the ability for the application to interact with log files, **Lolipop** let user to implement this because there are different ways to achieve durable logs (eg. file system, remote database).   

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

### 4. Define `PersistentIO`

Each raft node need to store some metadata durable, other than logs, the interaction with these metadata is a lot simpler, it's basic get and put, it is represented by `PersistentIO`

Here's an example
```scala
import cats.Monad
import cats.effect.concurrent.MVar
import raft.algebra.io.PersistentIO
import raft.model.Persistent

class SwayDBPersist[F[_]: Monad](db: swaydb.Map[Int, Persistent, F], lock: MVar[F, Unit]) extends PersistentIO[F] {
  val singleKey = 1

  override def get: F[Persistent] = db.get(singleKey).map(_.get)

  /**
    * The implementatFn of this method must persist
    * the `Persistent` atomically
    *
    * possible implementatFn:
    *   - JVM FileLock
    *   - embedded database
    */
  override def update(f: Persistent => Persistent): F[Unit] =
    for {
      _   <- lock.take
      old <- get
      new_ = f(old)
      _ <- db.put(singleKey, new_)
      _ <- lock.put(())
    } yield ()
}
```  

### 5. Define `EventLogger`

EventLogger is useful for debugging, it is being used to record important events in a Raft process. There is a `JsonEventLogger` provided out of the box.

TODO: Provide a slf4j event logger

### 6. Create `RaftProcess

Once we have the above dependencies, we can build a RaftProcess using methods on RaftProcess object, it requires all dependencies mentioned above, with an additional `ClusterConfig`, which is a case class containing names of each node 

WARNING: `ClusterConfig` will change once we support dynamic membership

```
import raft._

val clusterConfig = ClusterConfig("node0", Set("node1", "node2"))

val proc = RaftProcess.simple(
  stateMachine,
  clusterConfig,
  logIO,
  networkIO,
  eventLogger,
  persistentIO
)

```

### 7. Expose raft api to the network

Once you get a `RaftProcess`, you can do the following 

#### Bind client API to network

RaftProcess exposes `RaftApi` trait, which contains multiple method that should be binded to network

* `def write(cmd: Cmd): F[WriteResponse]`
* `def read: F[ReadResponse[State]]`
* `def requestAppend(req: AppendRequest[Cmd]): F[AppendResponse]`
* `def requestVote(req: VoteRequest): F[VoteResponse]`

User need to route network request to these methods

Here's an example of exposing methods by http api
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
          case GET -> Root / "state" =>
            for {
              reply <- api.read
              res   <- Ok(reply.asJson)
            } yield res
        }
        
    val server = BlazeServerBuilder[F]
        .bindHttp(9000,"localhost")
        // hook api to HTTP4S
        .withHttpApp(raftProtocol(raftProc.api).orNotFound) 
        .serve
```

#### Start the Raft Server

Once the methods are exposed, we can start the raft process, by doing this, raft process will run continuously to check if each nodes are reacheable.
 
You can start server by calling `def startRaft: Stream[F, Unit]` on RaftProcess, we typically evaluate the stream in Main class.

