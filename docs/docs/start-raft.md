---
layout: docs
title: Start the server
section: "usage"
---

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
