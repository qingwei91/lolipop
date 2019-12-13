package raft
package proptest
package kv

import cats.Eq
import cats.effect._
import cats.effect.concurrent.Ref

import org.scalacheck.Gen
import org.scalacheck.rng.Seed
import org.specs2.execute.Result
import org.specs2.specification.core.SpecStructure
import org.specs2.{ ScalaCheck, Specification }
import raft.algebra.StateMachine
import raft.proptest.checker._
import raft.proptest.cluster._

import scala.concurrent.ExecutionContext.global
import scala.concurrent.duration._
import scala.util.Random
import java.time.Instant
import raft.algebra.io.NetworkIO

@SuppressWarnings(
  Array(
    "org.wartremover.warts.Any",
    "org.wartremover.warts.Product",
    "org.wartremover.warts.Serializable",
    "org.wartremover.warts.OptionPartial"
  )
)
class RandomTest extends Specification with ScalaCheck {
  override def is: SpecStructure =
    s2"""
      KVStore should be linearizable
        failure - $testLessThanHalfNodeFailed
      """

  private implicit val cs: ContextShift[IO]       = IO.contextShift(global)
  private implicit val timer: Timer[IO]           = IO.timer(global)
  private implicit val kvEq: Eq[KVResult[String]] = Eq.fromUniversalEquals[KVResult[String]]

  private implicit val opsGen: Gen[List[KVOps]] = KVOps.gen(3)
  private implicit val multithreadedOps: Gen[List[(String, List[KVOps])]] = for {
    ops1 <- opsGen
    ops2 <- opsGen
  } yield {
    List("001" -> ops1, "002" -> ops2)
  }
  private val parFactor = 30

  private val stateMachines: IO[Map[String, StateMachine[IO, KVOps, KVResult[String]]]] = List("0", "1", "2")
    .traverse { id =>
      for {
        ref <- Ref[IO].of(Map.empty[String, String])
      } yield (id, KVOps.stateMachine(ref))
    }
    .map(_.toMap)

  private val genParam: Gen.Parameters = Gen.Parameters.default

  private def testSuccessfulRun = {

    /*
      Problem: Able to create test cases easily where
      I can specify fault scenario and get the resulting
      events, and check linearizability

      1. Define fault scenario
      2. Create operation plan
      3. Execute plan with fault scenario
     */
    val results = parTest(parFactor) { parId =>
      val rand = Random.nextLong()

      val opsPerThread: List[(String, List[KVOps])] = multithreadedOps(genParam, Seed(rand)).get

      for {
        results <- clusterResource.use(executeKVOps(opsPerThread)).time("Execution operations...")
        _ = println(s"Results is ${results.find(_.ret.isLeft)}")
        checkResult <- computeLinearizability(parId, results)
      } yield {
        checkResult match {
          case Linearizable(_) => success
          case NonLinearizable(longestAttempt, failed, exp) =>
            failure(s"Failed result = ${results.sortBy(_.startTime).mkString("\n")}")
        }
      }

    }
    Result.forall(results.unsafeRunSync())(identity)
  }

  private def testLessThanHalfNodeFailed = {

    /*
      Problem: Able to create test cases easily where
      I can specify fault scenario and get the resulting
      events, and check linearizability

      1. Define fault scenario
      2. Create operation plan
      3. Execute plan with fault scenario
     */
    val results = parTest(1) { parId =>
      val rand = Random.nextLong()

      val opsPerThread: List[(String, List[KVOps])] = multithreadedOps(genParam, Seed(rand)).get

      for {
        results <- clusterResource
                    .use { api =>
                      val faultA = List(
                        (api.stop("0"), api.stop("1")).parSequence,
                        api.stop("0"),
                        api.stop("1"),
                        api.start("1")
                      )

                      val faultB = List(
                        (api.stop("0"), api.stop("1")).parSequence,
                        api.stop("0"),
                        api.start("1")
                      )

                      val opsWithInjectedFault = opsPerThread
                        .map {
                          case ("001", opsA) => interleave(faultA, opsA.map(o => executeSingleOp("001", o, api)))
                          case ("002", opsB) => interleave(faultB, opsB.map(o => executeSingleOp("002", o, api)))
                        }
                        .parFlatTraverse { ops =>
                          ops
                            .traverse {
                              case Left(clusterOp) => clusterOp.map(Left(_))
                              case Right(kvOp) => kvOp.map(Right(_))
                            }
                            .map(_.collect {
                              case Right(fullOp) => fullOp
                            })
                        }
                      opsWithInjectedFault
                    }
                    .time("Execution operations...")
        _ = println(s"Results is ${results.mkString("\n")}")
        checkResult <- computeLinearizability(parId, results)
      } yield {
        checkResult match {
          case Linearizable(_) => success
          case NonLinearizable(longestAttempt, failed, exp) =>
            failure(s"Failed result = ${longestAttempt.mkString_("\n")}, exp=$exp, got $failed")

        }
      }

    }
    Result.forall(results.unsafeRunSync())(identity)

  }

  private def computeLinearizability(
    parId: Int,
    results: List[FullOperation[KVOps, KVResult[String]]]
  ): IO[LinearizedRes[FullOperation[KVOps, KVResult[String]]]] = {
    val history = History.fromList(results)

    LinearizationCheck
      .wAndG(
        history,
        KVOps.model,
        Map.empty[String, String]
      )
      .time(s"Linearization Check of $parId")
      .timeout(20.seconds)
  }

  private val startCluster: IO[ClusterApi[IO, KVOps, KVResult[String]]] = {
    for {
      pairs        <- stateMachines
      clusterState <- Ref[IO].of(ClusterState.init[IO])
      networkProxy = (network: NetworkIO[IO, KVOps]) => {
        new ProxiedNetwork(clusterState, network)
      }
      allNodes <- setupCluster(pairs, networkProxy)
      clusterApi = ClusterApi(allNodes, clusterState)
      _ <- clusterApi.startAll.time("Start cluster")
    } yield clusterApi
  }

  private val clusterResource: Resource[IO, ClusterApi[IO, KVOps, KVResult[String]]] =
    Resource.make(startCluster)(_.stopAll.time("Stop cluster"))

  private def executeSingleOp(
    threadId: String,
    op: KVOps,
    clusterApi: ClusterApi[IO, KVOps, KVResult[String]]
  ): IO[FullOperation[KVOps, KVResult[String]]] = {
    val apiRes = (op match {
      case Put(_, _) | Delete(_) =>
        clusterApi.write(op).map(Right(_): Either[Throwable, KVResult[String]])
      case Get(_) => clusterApi.read(op).map(Right(_): Either[Throwable, KVResult[String]])
    }).timeout(1.second).recover {
      case err: Throwable =>
        println(err)
        Either.left[Throwable, KVResult[String]](err)
    }

    for {
      start <- Timer[IO].clock.realTime(MILLISECONDS).map(Instant.ofEpochMilli)
      res   <- apiRes
      end   <- Timer[IO].clock.realTime(MILLISECONDS).map(Instant.ofEpochMilli)
    } yield {
      val realEnd = if (res.isLeft) {
        Instant.MAX
      } else {
        end
      }
      FullOperation(threadId, op, res, start, realEnd)
    }
  }
  private def executeKVOps(opsPerThread: List[(String, List[KVOps])])(
    clusterApi: ClusterApi[IO, KVOps, KVResult[String]]
  ): IO[List[FullOperation[KVOps, KVResult[String]]]] = {
    opsPerThread.parFlatTraverse {
      case (threadId, ops) =>
        ops.traverse { op =>
          executeSingleOp(threadId, op, clusterApi)
        }
    }
  }

}
