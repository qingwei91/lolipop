---
layout: docs
title: State Machine
section: "usage"
---

### 1. Implement `StateMachine`

Raft algorithm is used to implement a replicated state machine, represented by StateMachine in our code.

StateMachine is application specific, it manages the State that we are trying to reach consensus for. It could be as simple as an Int, or it could be a collection of documents. 


Below is a sample implementation, there are 2 method to implement
```scala
import cats.effect._
import cats.implicits._
import raft.algebra.StateMachine

case class ChangeCount(i: Int)

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
    override def getCurrent: IO[Int] = state.get
  }
}
```
