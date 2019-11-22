package raft
package proptest

import java.time.Instant

import cats.Show
import io.circe._
import io.circe.generic.auto._
import io.circe.syntax._

/**
  *  Decision: Should we model Invoke and Return in 1 data structure?
  *  Pros:
  *  1. Remove the need of stiching things together
  *  2. Does not lose the ability to treat them separately
  */
sealed trait Event[+O, +R] {
  def threadId: String
  def time: Long
}
case class Invoke[Op](threadId: String, op: Op, time: Long) extends Event[Op, Nothing]
case class Ret[R](threadId: String, result: R, time: Long) extends Event[Nothing, R]

// explicitly model Failure in event, because Event is a polymorphic
// type that can be handled by checker without the knowledge of
// the actual API protocol
// by having Failure in Event, checker can handle Failure
// explicitly and figure out how to handle it
case class Failed(threadId: String, err: Throwable, time: Long) extends Event[Nothing, Nothing]

@SuppressWarnings(Array("org.wartremover.warts.All"))
object Event {
  implicit def showEvent[I: Show, O: Show]: Show[Event[I, O]] = Show.show {
    case Invoke(threadId, op, time) => s"Thread $threadId - Invoke ${op.show} at ${Instant.ofEpochMilli(time)}"
    case Ret(threadId, result, time) => s"Thread $threadId - Return ${result.show} at ${Instant.ofEpochMilli(time)}"
    case Failed(threadId, err, time) =>
      s"Thread $threadId - Failed with ${err.getMessage} at ${Instant.ofEpochMilli(time)}"
  }

  implicit val throwableCodec: Codec[Throwable] = new Codec[Throwable] {
    def apply(c: HCursor): Decoder.Result[Throwable] = c.as[String].map(s => new Exception(s))
    def apply(a: Throwable): Json                    = Json.fromString(a.getMessage)
  }

  implicit def codec[A: Codec, B: Codec]: Codec[Event[A, B]] = Codec.from(
    List[Decoder[Event[A, B]]](
      Decoder[Invoke[A]].widen,
      Decoder[Ret[B]].widen,
      Decoder[Failed].widen
    ).reduce(_ or _),
    Encoder.instance[Event[A, B]] {
      case i: Invoke[A] => i.asJson
      case r: Ret[B] => r.asJson
      case f: Failed => f.asJson
    }
  )
}

case class FullOperation[O, R](threadId: String, op: O, ret: Either[Throwable, R], startTime: Long, endTime: Long)

object FullOperation {
  implicit def showFullOp[A: Show, B: Show]: Show[FullOperation[A, B]] = Show.show {
    fullOp =>
      s"""
         |Thread ${fullOp.threadId}
         |- invoked ${fullOp.op}
         |- return ${fullOp.ret}""".stripMargin
  }

  implicit val throwableCodec: Codec[Throwable] = new Codec[Throwable] {
    def apply(c: HCursor): Decoder.Result[Throwable] = c.as[String].map(s => new Exception(s))
    def apply(a: Throwable): Json                    = Json.fromString(a.getMessage)
  }

  implicit def codec[A: Codec, B: Codec]: Codec[FullOperation[A, B]] = {
    Codec.from(
      Decoder[FullOperation[A, B]],
      Encoder[FullOperation[A, B]]
    )
  }

}
