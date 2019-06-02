package raft

import java.io.File
import java.util.concurrent.Executors

import cats.Semigroup
import cats.data._
import cats.effect._
import org.specs2.Specification
import org.specs2.execute.Result
import org.specs2.matcher.MatchResult
import org.specs2.specification.core.SpecStructure
import raft.RaftReplicationSpec._
import raft.model._
import raft.setup._

import scala.concurrent.ExecutionContext
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.util.Random

@SuppressWarnings(Array("org.wartremover.warts.All"))
class RaftReplicationSpec extends Specification {

  override def is: SpecStructure =
    s2"""
        Raft
          should
          - replicate logs and commit ${eventuallyReplicated(3)}
          - replicate logs when less than half failed - $replicateIfMoreThanHalf
          - not replicate if more than half failed - $dontReplicateIfLessThanHalf
      """

  def eventuallyReplicated(n: Int = 1): Result = {
    val executor                        = Executors.newFixedThreadPool(4)
    val ecToUse                         = ExecutionContext.fromExecutor(executor)
    implicit val ioCS: ContextShift[IO] = IO.contextShift(ecToUse)
    implicit val ioTM: Timer[IO]        = IO.timer(ecToUse)

    val allResults = NonEmptyList.of(0, (1 to 20).toList).parTraverse { _ =>
      val deps = new RaftTestDeps[IO]()
      import deps._

      val check_logs_replicated = assertWithServers(tasksIO) { raftComponents =>
        val statesOfAllNode = raftComponents.map(_.state)
        val clients = raftComponents.map { components =>
          components.state.config.nodeId -> components.api
        }.toNem

        val commands = NonEmptyList.fromListUnsafe((0 to n).map(i => s"Cmd$i").toList)

        val writeRequests = commands.parTraverse { cmd =>
          TestClient.writeToLeader(clients.toSortedMap)("0", cmd)
        }
        val readReq = TestClient.readFromLeader(clients.toSortedMap)("0")

        for {
          _        <- ioTM.sleep(timeToReplication) // allow time for election to avoid contention
          writeRes <- writeRequests.timeout(timeToReplication * 2)
          readRes  <- readReq
          allLogs  <- statesOfAllNode.parTraverse(_.logs.lastLog)
          commitIndices <- statesOfAllNode.parTraverse { state =>
                            state.serverTpe.get.map {
                              case l: Leader => Some(l.commitIdx)
                              case _ => None
                            }
                          }
        } yield {
          val logReplicated = allLogs.collect {
            case Some(a) if a.idx == n + 1 =>
              a
          }.size must be_>=(3)

          val logCommittedBySome = commitIndices.collectFirst { case Some(x) => x } must beSome(n + 1)

          val elected = writeRes.map(_ must_!== NoLeader).reduce

          val finalState = readRes.asInstanceOf[Read[String]].state

          val stateContainsAllCommands = commands.map(cmd => finalState must contain(cmd)).reduce

          NonEmptyList
            .of(
              logCommittedBySome,
              elected,
              logReplicated,
              stateContainsAllCommands
            )
            .reduce
        }
      }
      check_logs_replicated
    }

    try {
      allResults.unsafeRunSync().reduce
    } catch {
      case t: Throwable => failure(s"Unexpected failure ${t.getMessage}")
    } finally {
      executor.shutdown()
    }
  }

  def isEven(i: Int): Boolean = i % 2 == 0
  val splitbrain = (from: String, to: String) => {
    isEven(from.toInt) != isEven(to.toInt)
  }

  def replicateIfMoreThanHalf: Result = {
    val ecToUse                         = global
    implicit val ioCS: ContextShift[IO] = IO.contextShift(ecToUse)
    implicit val ioTM: Timer[IO]        = IO.timer(ecToUse)

    val deps = new RaftTestDeps[IO](shouldFail = splitbrain)
    import deps._

    val checkLogCommitted = assertWithServers(tasksIO) { testData =>
      val statesOfAllNode = testData.map(_.state)
      val clients = testData.map { components =>
        components.state.config.nodeId -> components.api
      }.toNem

      val clientResIO = TestClient.writeToLeader(clients.toSortedMap)("0", "Cmd1")

      for {
        // allow time for election to avoid contention
        _         <- ioTM.sleep(timeToReplication)
        clientRes <- clientResIO.timeout(timeToReplication * 3)
        _         <- ioTM.sleep(timeToReplication)
        allLogs <- statesOfAllNode.parTraverse { state =>
                    state.logs.lastLog
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

    checkLogCommitted.unsafeRunSync()
  }

  def dontReplicateIfLessThanHalf: Result = {
    implicit val ioCS = IO.contextShift(global)
    implicit val ioTM = IO.timer(global)
    val moreThanHalfDown = (from: String, to: String) => {
      Set(from.toInt, to.toInt) != Set(1, 2)
    }

    val deps = new RaftTestDeps[IO](shouldFail = moreThanHalfDown)
    import deps._

    val checkLogCommitted = assertWithServers(tasksIO) { testData =>
      val statesOfAllNode = testData.map(_.state)
      val clients = testData.map { components =>
        components.state.config.nodeId -> components.api
      }.toNem

      val clientResIO = IO
        .race(
          ioTM.sleep(timeToReplication).as(NoLeader),
          TestClient.writeToLeader(clients.toSortedMap)("0", "Cmd")
        )
        .map(_.merge)

      for {
        _         <- ioTM.sleep(timeToReplication) // allow time for election to avoid contention
        clientRes <- clientResIO
        allLogs <- statesOfAllNode.parTraverse { state =>
                    state.logs.lastLog
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

    checkLogCommitted.unsafeRunSync()

  }

  implicit def resultSemigroup[A]: Semigroup[MatchResult[A]] = new Semigroup[MatchResult[A]] {
    override def combine(x: MatchResult[A], y: MatchResult[A]): MatchResult[A] = {
      x and y
    }
  }
}

object RaftReplicationSpec {
  val timeToReplication = 3.seconds

  @SuppressWarnings(Array("org.wartremover.warts.All"))
  def assertWithServers[A](
    allRaftProcesses: IO[NonEmptyList[RaftTestComponents[IO]]]
  )(
    fn: NonEmptyList[RaftTestComponents[IO]] => IO[MatchResult[A]],
    name: String = "test"
  )(implicit CS: ContextShift[IO], TM: Timer[IO]): IO[MatchResult[A]] = {
    allRaftProcesses.flatMap { ts =>
      ts.parTraverse { components =>
          val startedPoller = components.proc.startRaft.compile.drain.start
          startedPoller.map(components -> _)
        }
        .bracket { all =>
          val components = all.map(_._1)
          val assertion  = fn(components)
          val rand       = Random.nextInt(10000)
          val flushLogsToFile = components.traverse { comp =>
            val nodeId = comp.eventLogger.nodeId
            comp.flushLogTo(new File(s"$name-$rand-$nodeId.log"))
          }

          assertion
            .timeout(timeToReplication * 5)
            .onError { case _: Throwable => flushLogsToFile.void }
            .flatMap {
              case ok if ok.isSuccess => ok.pure[IO]
              case nok => flushLogsToFile.as(nok)
            }
        }(_.parTraverse_(_._2.cancel))
    }
  }

}
