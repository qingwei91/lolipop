package raft.http

import java.nio.file.Paths

import cats.effect.concurrent.Ref
import cats.effect.{ ExitCode, IO, IOApp }
import cats.implicits._
import cats.{ Eq, ~> }
import io.circe.generic.auto._
import org.http4s.circe._
import org.http4s.{ Uri, dsl }
import pureconfig.ConfigReader
import pureconfig.generic.auto._
import raft.algebra.StateMachine
import raft.model.RaftLog
import swaydb.data
import swaydb.data.io.{ FutureTransformer, Wrap }
import swaydb.data.slice.Slice
import swaydb.serializers.Default._
import swaydb.serializers.Serializer

import scala.concurrent.Future
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._

@SuppressWarnings(
  Array(
    "org.wartremover.warts.Any"
  )
)
object RaftHttpExample extends IOApp with CirceEntityDecoder {
  implicit val readURI = ConfigReader[String].map(Uri.unsafeFromString)

  implicit object raftLogSerializer extends Serializer[RaftLog[ChangeCount]] {
    override def write(data: RaftLog[ChangeCount]): Slice[Byte] = {
      Slice
        .create[Byte](100)
        .addInt(data.idx)
        .addInt(data.term)
        .addInt(data.command.i)
        .close()
    }

    override def read(data: Slice[Byte]): RaftLog[ChangeCount] = {
      val reader = data.createReader()
      val idx    = reader.readInt()
      val term   = reader.readInt()
      val i      = reader.readInt()
      RaftLog(idx, term, ChangeCount(i))
    }
  }

  val nodeId = sys.env.getOrElse("NODE_ID", "1")
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

  val nt = new (swaydb.data.IO ~> IO) {
    override def apply[A](fa: data.IO[A]): IO[A] = {
      fa match {
        case data.IO.Success(a) => IO.pure(a)
        case data.IO.Failure(err) => IO.raiseError(err.exception)
      }
    }
  }
  implicit object IOTransformer extends FutureTransformer[IO] {
    override def toOther[I](future: Future[I]): IO[I] = IO.fromFuture(IO(future))

    override def toFuture[I](io: IO[I]): Future[I] = io.unsafeToFuture()
  }

  implicit val ioWrap: Wrap[IO] = Wrap.buildAsyncWrap[IO](IOTransformer, 10.seconds)

  private val dbPath = Paths.get("raft-sample")
  private val db     = swaydb.persistent.Map[Int, RaftLog[ChangeCount]](dbPath)

  private val ioDB = nt(db).map { inner =>
    new FileLogIO(inner.asyncAPI[IO])
  }

  override def run(args: List[String]): IO[ExitCode] = {
    implicit val cmdEq = Eq.fromUniversalEquals[ChangeCount]

    RaftHttpServer[IO, ChangeCount, Int](
      nodeId,
      networkConfs,
      counter,
      dsl.io,
      ioDB,
      ChangeCount(0)
    ).start
      .compile[IO, IO, ExitCode]
      .drain
      .as(ExitCode.Success)
  }
}
