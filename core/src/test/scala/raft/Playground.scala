package raft

import cats.effect.IO

import scala.concurrent.ExecutionContext.global
import scala.concurrent.duration._

object Playground {
  implicit val cs = IO.contextShift(global)
  implicit val tm = IO.timer(global)
  val throwAfter10 = IO.sleep(1.seconds) *> IO.raiseError(new Error("laalla"))

  throwAfter10
    .start
    .map(_ => println("ignore wat"))
    .unsafeRunSync()

  Thread.sleep(1000)
}
