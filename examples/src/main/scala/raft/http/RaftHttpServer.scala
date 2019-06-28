package raft.http

import java.io.{ BufferedWriter, FileWriter, PrintWriter }

import cats._
import cats.effect._
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
import raft.algebra.io.{ LogsApi, MetadataIO }
import raft.debug.JsonEventLogger
import raft.model._
import raft.{ RaftApi, RaftProcess }

import scala.concurrent.ExecutionContext.global

trait RaftHttpServer[F[_]] {
  def start: fs2.Stream[F, ExitCode]
}

@SuppressWarnings(Array("org.wartremover.warts.Any"))
object RaftHttpServer extends CirceEntityDecoder with KleisliSyntax {

  def apply[F[_]: ConcurrentEffect: Timer: Monad: ContextShift, Cmd: Decoder: Encoder: Eq: Show, State: Show: Encoder](
                                                                                                                        nodeID: String,
                                                                                                                        networkMapping: Map[String, Uri],
                                                                                                                        stateMachineF: F[StateMachine[F, Cmd, State]],
                                                                                                                        httpDSL: Http4sDsl[F],
                                                                                                                        logIOF: F[LogsApi[F, Cmd]],
                                                                                                                        persistIOF: F[MetadataIO[F]]
  ): RaftHttpServer[F] = {
    import httpDSL._

    def raftProtocol(api: RaftApi[F, Cmd, State]): HttpRoutes[F] =
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
              reply  <- api.write(change)
              res    <- Ok(reply.asJson)
            } yield res
          case GET -> Root / "state" =>
            for {
              reply <- api.read
              res   <- Ok(reply.asJson)
            } yield res
        }

    val config = ClusterConfig(nodeID, networkMapping.keySet - nodeID)

    val fileResource = Stream.bracket(
      Defer[F].defer(
        new PrintWriter(
          new BufferedWriter(
            new FileWriter("hardcoded.json", true)
          )
        ).pure[F]
      )
    )(writer => Applicative[F].pure(writer.close()))

    val raftProcess: Stream[F, RaftProcess[F, Cmd, State]] =
      for {
        client <- BlazeClientBuilder.apply[F](global).stream
        network = new HttpNetwork[F, Cmd](networkMapping, client)
        stateM    <- Stream.eval(stateMachineF)
        logIO     <- Stream.eval(logIOF)
        persistIO <- Stream.eval(persistIOF)
        printer   <- fileResource
        eventLogger = new JsonEventLogger[F, Cmd, State](
          s => Defer[F].defer(Applicative[F].pure(printer.println(s)))
        )
        proc <- Stream.eval(
                 RaftProcess.init(
                   stateM,
                   config,
                   logIO,
                   network,
                   eventLogger,
                   persistIO
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
