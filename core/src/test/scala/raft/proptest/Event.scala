package raft
package proptest

import cats.Show
import io.circe._
import io.circe.generic.auto._
import io.circe.syntax._

sealed trait Event[+O, +R] {
  def threadId: String
}
case class Invoke[Op](threadId: String, op: Op) extends Event[Op, Nothing]
case class Ret[R](threadId: String, result: R) extends Event[Nothing, R]

// explicitly model Failure in event, because Event is a polymorphic
// type that can be handled by checker without the knowledge of
// the actual API protocol
// by having Failure in Event, checker can handle Failure
// explicitly and figure out how to handle it
case class Failed(threadId: String, err: Throwable) extends Event[Nothing, Nothing]

@SuppressWarnings(Array("org.wartremover.warts.All"))
object Event {
  implicit def showEvent[I: Show, O: Show]: Show[Event[I, O]] = Show.show {
    case Invoke(threadId, op) => s"Thread $threadId - Invoke ${op.show}"
    case Ret(threadId, result) => s"Thread $threadId - Return ${result.show}"
    case Failed(threadId, err) => s"Thread $threadId - Failed with ${err.getMessage}"
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
