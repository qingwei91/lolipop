package raft

import cats.Semigroup
import cats.data._
import cats.effect._
import org.specs2.execute.Result
import org.specs2.matcher.MatchResult
import org.specs2.specification.core.SpecStructure
import org.specs2.{ ScalaCheck, Specification }
import raft.algebra.client.ClientIncoming
import raft.model._
import raft.setup.TestClient

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._

@SuppressWarnings(Array("org.wartremover.warts.All"))
class RaftReplicationSpec extends Specification with ScalaCheck {
  override def is: SpecStructure =
    s2"""
        Raft
          should
          - replicate logs and commit ${eventuallyReplicated(3)}
          - replicate logs when less than half failed - $replicateIfMoreThanHalf
          - not replicate if more than half failed - $dontReplicateIfLessThanHalf
      """

  private val timeToReplication = 3.seconds

  def eventuallyReplicated(n: Int = 1): Result = {
    val deps = new RaftTestDeps(global)
    import deps._

    val checkLogCommitted = withServer(tasksIO) { testData =>
      val statesOfAllNode = testData.map(_._2)
      val clients = testData.map {
        case (clientIncoming, state) =>
          state.config.nodeId -> clientIncoming
      }.toNem
      val request = (0 to n).toList.parTraverse { i =>
        TestClient.untilCommitted(clients.toSortedMap)("0", s"Cmd$i")
      }
      for {
        responses <- request.timeout(timeToReplication * 2)
        _         <- tm.sleep(timeToReplication)
        allLogs <- statesOfAllNode.parTraverse { state =>
                    state.persistent.get.map(_.logs)
                  }
        commitIndices <- statesOfAllNode.parTraverse { state =>
                          state.serverTpe.get.map(_.commitIdx)
                        }
      } yield {
        val logReplicated = allLogs
          .map(_.size must_=== n + 1)
          .reduce

        val logCommittedByAll = commitIndices.map(_ must_=== n + 1).reduce
        val elected           = NonEmptyList.fromListUnsafe(responses).map(_ must_!== NoLeader).reduce

        logCommittedByAll and elected and logReplicated
      }
    }

    checkLogCommitted.unsafeRunTimed(10.seconds).get
  }

  def replicateIfMoreThanHalf: Result = {
    def isEven(i: Int): Boolean = i % 2 == 0
    val splitbrain = (from: String, to: String) => {
      isEven(from.toInt) != isEven(to.toInt)
    }

    val deps = new RaftTestDeps(global, shouldFail = splitbrain)
    import deps._

    val checkLogCommitted = withServer(tasksIO) { testData =>
      val statesOfAllNode = testData.map(_._2)
      val clients = testData.map {
        case (clientIncoming, state) =>
          state.config.nodeId -> clientIncoming
      }.toNem
      val clientResIO = TestClient.untilCommitted(clients.toSortedMap)("0", "Cmd1").timeout(timeToReplication * 2)

      for {
        clientRes <- clientResIO
        _         <- tm.sleep(timeToReplication)
        allLogs <- statesOfAllNode.parTraverse { state =>
                    state.persistent.get.map(_.logs)
                  }
        commitIndices <- statesOfAllNode.parTraverse { state =>
                          state.serverTpe.get.map(_.commitIdx)
                        }
      } yield {

        /**
          * assert to be more than 3 because client might hit a
          */
        val logReplicated = allLogs.count { logsPerNode =>
          logsPerNode.exists(_.command == "Cmd1")
        } must_=== 3

        val logCommitted = commitIndices.count(_ == 1) must_=== 3
        val elected      = clientRes must_!== NoLeader
        logCommitted and elected and logReplicated
      }
    }

    checkLogCommitted
      .unsafeRunTimed(10.seconds)
      .get
  }

  def dontReplicateIfLessThanHalf: Result = {

    val moreThanHalfDown = (from: String, to: String) => {
      Set(from.toInt, to.toInt) != Set(1, 2)
    }

    val deps = new RaftTestDeps(global, shouldFail = moreThanHalfDown)
    import deps._

    val checkLogCommitted = withServer(tasksIO) { testData =>
      val statesOfAllNode = testData.map(_._2)
      val clients = testData.map {
        case (clientIncoming, state) =>
          state.config.nodeId -> clientIncoming
      }.toNem

      val clientResIO = IO
        .race(
          tm.sleep(timeToReplication).as(NoLeader),
          TestClient.untilCommitted(clients.toSortedMap)("0", "Cmd")
        )
        .map(_.merge)

      for {
        clientRes <- clientResIO
        allLogs <- statesOfAllNode.parTraverse { f =>
                    f.persistent.get.map(_.logs)
                  }
        commitIndices <- statesOfAllNode.parTraverse { f =>
                          f.serverTpe.get.map(_.commitIdx)
                        }
      } yield {
        val logNotReplicated = allLogs.count(_.nonEmpty) must_=== 0

        val noLogCommitted = commitIndices.count(_ == 1) must_=== 0
        val noLeader       = clientRes must_=== NoLeader
        noLogCommitted and noLeader and logNotReplicated
      }
    }

    checkLogCommitted
      .unsafeRunTimed(10.seconds)
      .get
  }

  private def withServer[A](
    tasks: IO[NonEmptyList[RaftTestComponents]]
  )(
    f: NonEmptyList[(ClientIncoming[IO, String], RaftNodeState[IO, String])] => IO[A]
  )(implicit CS: ContextShift[IO]): IO[A] = {
    tasks.flatMap { ts =>
      ts.parTraverse { components =>
          val startedPoller = components.proc.startRaft.compile.drain.start

          startedPoller.map { fiber =>
            components -> fiber
          }
        }
        .bracket { all =>
          val resourcesToExpose = all.map {
            case (RaftTestComponents(_, client, state), _) => client -> state
          }
          f(resourcesToExpose)
        }(_.parTraverse_(_._2.cancel))
    }
  }

  implicit def resultSemigroup[A]: Semigroup[MatchResult[A]] = new Semigroup[MatchResult[A]] {
    override def combine(x: MatchResult[A], y: MatchResult[A]): MatchResult[A] = {
      x and y
    }
  }
}
