package raft.http

import cats.effect.concurrent.Ref
import cats.effect.{ ExitCode, IO, IOApp }
import cats.implicits._
import cats.Eq
import io.circe.generic.auto._
import org.http4s.circe._
import org.http4s.{ Uri, dsl }
import pureconfig.ConfigReader
import raft.algebra.StateMachine

@SuppressWarnings(
  Array(
    "org.wartremover.warts.Any"
  )
)
object Raft extends IOApp with CirceEntityDecoder {
  implicit val readURI = ConfigReader[String].map(Uri.unsafeFromString)
  val nodeId           = sys.env.getOrElse("NODE_ID", "1")
  val networkConfs = {
    val loaded   = pureconfig.loadConfig[List[RawConfig]]("nodes")
    val allConfs = loaded.getOrElse(Nil)
    allConfs.map { rc =>
      rc.id -> rc.uri
    }.toMap
  }

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

  override def run(args: List[String]): IO[ExitCode] = {
    implicit val cmdEq = Eq.fromUniversalEquals[ChangeCount]

    RaftHttpServer[IO, IO.Par, ChangeCount, Int](
      nodeId,
      networkConfs,
      counter,
      dsl.io,
      ChangeCount(0)
    ).start
      .compile[IO, IO, ExitCode]
      .drain
      .as(ExitCode.Success)
  }
}
