package raft
package proptest
package kv

import cats.Show
import io.circe.{ Codec, Decoder, Encoder }
import io.circe.syntax._
import io.circe.generic.auto._

sealed trait KVResult[+A]
case class ReadOK[A](read: Option[A]) extends KVResult[A]
case class WriteOK[A](k: String, v: A) extends KVResult[A]
case class Deleted(deleted: String) extends KVResult[Nothing]

@SuppressWarnings(Array("org.wartremover.warts.TraversableOps"))
object KVResult {
  implicit def showResult[A]: Show[KVResult[A]] = Show.show {
    case ReadOK(Some(a)) => s"Read $a"
    case ReadOK(None) => "Read None"
    case WriteOK(_, _) => "Written"
    case Deleted(deleted) => s"Deleted $deleted"
  }

  implicit def codec[A: Codec]: Codec[KVResult[A]] = Codec.from(
    List[Decoder[KVResult[A]]](
      Decoder[WriteOK[A]].widen,
      Decoder[Deleted].widen,
      Decoder[ReadOK[A]].widen
    ).reduce(_ or _),
    Encoder.instance {
      case w: WriteOK[A] => w.asJson
      case r: ReadOK[A] => r.asJson
      case d: Deleted => d.asJson
    }
  )
}
