package raft.http

import cats._
import cats.effect._
import cats.effect.concurrent.Ref
import cats.implicits._
import fs2.Stream
import io.circe.generic.auto._
import io.circe.syntax._
import io.circe.{ Decoder, Encoder }
import org.http4s._
import org.http4s.circe._
import org.http4s.client.blaze.BlazeClientBuilder
import org.http4s.dsl.Http4sDsl
import org.http4s.server.blaze.BlazeServerBuilder
import org.http4s.syntax._
import raft.algebra.StateMachine
import raft.algebra.io.LogIO
import raft.model._
import raft.{ RaftApi, RaftProcess }

import scala.concurrent.ExecutionContext.global

trait RaftHttpServer[F[_]] {
  def start: fs2.Stream[F, ExitCode]
}

@SuppressWarnings(Array("org.wartremover.warts.Any"))
object RaftHttpServer extends CirceEntityDecoder with KleisliSyntax {

  def apply[F[_]: ConcurrentEffect: Timer: Monad: ContextShift, FF[_], Cmd: Decoder: Encoder: Eq, State](
    nodeID: String,
    networkMapping: Map[String, Uri],
    stateMachineF: F[StateMachine[F, Cmd, State]],
    httpDSL: Http4sDsl[F],
    logsIO: F[LogIO[F, Cmd]],
    initCmd: Cmd
  )(implicit P: Parallel[F, FF]): RaftHttpServer[F] = {
    import httpDSL._

    type Log = RaftLog[Cmd]

    def raftProtocol(api: RaftApi[F, Cmd]): HttpRoutes[F] =
      HttpRoutes
        .of[F] {
          case req @ POST -> Root / "append" =>
            for {
              app   <- req.as[AppendRequest[Log]]
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

    val config = ClusterConfig(nodeID, networkMapping.keySet - nodeID)

    val raftProcess: Stream[F, RaftProcess[F, Cmd]] = for {
      client <- BlazeClientBuilder.apply[F](global).stream
      network = new HttpNetwork[F, Log](networkMapping, client)
      stateM     <- Stream.eval(stateMachineF)
      persistent <- Stream.eval(Ref.of[F, Persistent](Persistent.init))
      log <- Stream.eval(logsIO)
      proc <- Stream.eval(
               RaftProcess.simple(
                 stateM,
                 config,
                 log,
                 network,
                 persistent,
                 initCmd
               )
             )
    } yield proc

    new RaftHttpServer[F] {
      @SuppressWarnings(Array("org.wartremover.warts.Any"))
      override def start: fs2.Stream[F, ExitCode] = {
        for {
          raft <- raftProcess
          server = BlazeServerBuilder[F]
            .bindHttp(
              networkMapping(nodeID).authority
                .flatMap(_.port)
                .getOrElse(9000),
              "localhost"
            )
            .withHttpApp(raftProtocol(raft.api).orNotFound)
            .serve
          code <- raft.startRaft.as(ExitCode.Success).merge(server)
        } yield code
      }
    }
  }
}
