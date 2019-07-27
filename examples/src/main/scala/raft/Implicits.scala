package raft

import cats.effect.IO
import cats.~>
import org.http4s.Uri
import pureconfig.generic.auto._
import pureconfig.ConfigReader
import raft.model.Metadata
import swaydb.data
import swaydb.data.io.{ FutureTransformer, Wrap }
import swaydb.data.slice.Slice
import swaydb.serializers.Serializer

import scala.concurrent.{ ExecutionContext, Future }
import scala.concurrent.duration._

object Implicits {
  implicit val readURI = ConfigReader[String].map(Uri.unsafeFromString)
  implicit object persistSerializer extends Serializer[Metadata] {
    override def write(data: Metadata): Slice[Byte] =
      Slice.create[Byte](100).addInt(data.currentTerm).addString(data.votedFor.getOrElse(""))

    override def read(data: Slice[Byte]): Metadata = {
      val reader   = data.createReader()
      val currentT = reader.readInt()
      val votedRaw = reader.readRemainingAsString()
      val votedFor = if (votedRaw == "") None else Some(votedRaw)
      Metadata(currentT, votedFor)
    }
  }
  implicit object IOTransformer extends FutureTransformer[IO] {
    override def toOther[I](future: Future[I]): IO[I] = IO.fromFuture(IO(future))

    override def toFuture[I](io: IO[I]): Future[I] = io.unsafeToFuture()
  }
  implicit def ioWrap(implicit ec: ExecutionContext): Wrap[IO] = Wrap.buildAsyncWrap[IO](IOTransformer, 10.seconds)
  val swayDBToCatsIO = new (swaydb.data.IO ~> IO) {
    override def apply[A](fa: data.IO[A]): IO[A] = {
      fa match {
        case data.IO.Success(a) => IO.pure(a)
        case data.IO.Failure(err) => IO.raiseError(err.exception)
      }
    }
  }
}
