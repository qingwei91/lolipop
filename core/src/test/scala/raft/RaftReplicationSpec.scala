package raft

import java.io.{ File, PrintWriter }
import java.util.concurrent.Executors

import cats.Semigroup
import cats.data._
import cats.effect._
import org.scalacheck.Prop
import org.specs2.execute.Result
import org.specs2.matcher.MatchResult
import org.specs2.scalacheck.Parameters
import org.specs2.specification.core.SpecStructure
import org.specs2.{ ScalaCheck, Specification }
import raft.model._
import raft.setup.TestClient

import scala.concurrent.ExecutionContext
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.util.Random

import RaftReplicationSpec._

@SuppressWarnings(Array("org.wartremover.warts.All"))
class RaftReplicationSpec extends Specification with ScalaCheck {

  implicit val params: Parameters = Parameters(minTestsOk = 20, workers = 4).verbose
  override def is: SpecStructure =
    s2"""
        Raft
          should
          - replicate logs and commit ${eventuallyReplicated(3)}
          - replicate logs when less than half failed - $replicateIfMoreThanHalf
          - not replicate if more than half failed - $dontReplicateIfLessThanHalf
      """

  private val timeToReplication = 3.seconds

  def eventuallyReplicated(n: Int = 1): Prop =
    prop { _: Int =>
      val executor      = Executors.newFixedThreadPool(4)
      val ecToUse       = ExecutionContext.fromExecutor(executor)
      implicit val ioCS = IO.contextShift(ecToUse)
      implicit val ioTM = IO.timer(ecToUse)

      val deps = new RaftTestDeps[IO]()
      import deps._

      val checkLogCommitted = withServer(tasksIO) { testData =>
        val statesOfAllNode = testData.map(_.state)
        val clients = testData.map { components =>
          components.state.config.nodeId -> components.clientIncoming
        }.toNem
        val request = (0 to n).toList.parTraverse { i =>
          TestClient.untilCommitted(clients.toSortedMap)("0", s"Cmd$i")
        }
        val loggerIO = testData.map(_.eventLogger)

        val ioAssertion = for {
          _         <- ioTM.sleep(timeToReplication) // allow time for election to avoid contention
          responses <- request.timeout(timeToReplication * 2)
          allLogs   <- statesOfAllNode.parTraverse(_.logs.lastLog)
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

          val logCommittedByAll = commitIndices.collectFirst { case Some(x) => x } must_=== Some(n + 1)

          val elected = NonEmptyList.fromListUnsafe(responses).map(_ must_!== NoLeader).reduce

          val assertion = logCommittedByAll and elected and logReplicated
          if (assertion.isSuccess) {
            assertion
          } else {
            assertion
          }
        }

        val debugLog = for {
          pair <- loggerIO.traverse { logger =>
                   logger.logs.get.map(_ -> logger.nodeId)
                 }
        } yield {
          val rand = Random.nextInt(1000000)
          pair.map {
            case (str, idx) =>
              val pw = new PrintWriter(new File(s"logs-$rand-$idx.log"))
              pw.println(str.toString)
              pw.close()
          }
        }

        ioAssertion
          .onError {
            case _: Throwable => debugLog.as(())
          }
          .flatMap { assertion =>
            if (assertion.isSuccess) {
              IO.pure(assertion)
            } else {
              debugLog *> IO.pure(assertion)
            }

          }
      }
      try {
        checkLogCommitted
          .unsafeRunTimed(timeToReplication * 5)
          .getOrElse(
            0 must_== 1
          )
      } catch {
        case _: Throwable => 0 must_== 1
      } finally {
        executor.shutdown()
      }
    }

  def replicateIfMoreThanHalf: Result = {
    implicit val ioCS = IO.contextShift(global)
    implicit val ioTM = IO.timer(global)

    def isEven(i: Int): Boolean = i % 2 == 0
    val splitbrain = (from: String, to: String) => {
      isEven(from.toInt) != isEven(to.toInt)
    }

    val deps = new RaftTestDeps[IO](shouldFail = splitbrain)
    import deps._

    val checkLogCommitted = withServer(tasksIO) { testData =>
      val statesOfAllNode = testData.map(_.state)
      val clients = testData.map { components =>
        components.state.config.nodeId -> components.clientIncoming
      }.toNem

      val clientResIO = TestClient.untilCommitted(clients.toSortedMap)("0", "Cmd1")

      for {
        _         <- ioTM.sleep(timeToReplication) // allow time for election to avoid contention
        clientRes <- clientResIO.timeout(timeToReplication * 2)
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

    checkLogCommitted
      .unsafeRunTimed(10.seconds)
      .get
  }

  def dontReplicateIfLessThanHalf: Result = {
    implicit val ioCS = IO.contextShift(global)
    implicit val ioTM = IO.timer(global)
    val moreThanHalfDown = (from: String, to: String) => {
      Set(from.toInt, to.toInt) != Set(1, 2)
    }

    val deps = new RaftTestDeps[IO](shouldFail = moreThanHalfDown)
    import deps._

    val checkLogCommitted = withServer(tasksIO) { testData =>
      val statesOfAllNode = testData.map(_.state)
      val clients = testData.map { components =>
        components.state.config.nodeId -> components.clientIncoming
      }.toNem

      val clientResIO = IO
        .race(
          ioTM.sleep(timeToReplication).as(NoLeader),
          TestClient.untilCommitted(clients.toSortedMap)("0", "Cmd")
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

    checkLogCommitted
      .unsafeRunTimed(10.seconds)
      .get
  }

  implicit def resultSemigroup[A]: Semigroup[MatchResult[A]] = new Semigroup[MatchResult[A]] {
    override def combine(x: MatchResult[A], y: MatchResult[A]): MatchResult[A] = {
      x and y
    }
  }
}

object RaftReplicationSpec {
  @SuppressWarnings(Array("org.wartremover.warts.All"))
  def withServer[A](
    tasks: IO[NonEmptyList[RaftTestComponents[IO]]]
  )(f: NonEmptyList[RaftTestComponents[IO]] => IO[A])(implicit CS: ContextShift[IO]): IO[A] = {
    tasks.flatMap { ts =>
      ts.parTraverse { components =>
          val startedPoller = components.proc.startRaft.compile.drain.start

          startedPoller.map { fiber =>
            components -> fiber
          }
        }
        .bracket { all =>
          val resourcesToExpose = all.map {
            case (components, _) => components
          }
          f(resourcesToExpose)
        }(_.parTraverse_(_._2.cancel))
    }
  }
}
