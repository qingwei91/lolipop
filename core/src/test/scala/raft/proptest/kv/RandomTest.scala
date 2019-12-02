package raft
package proptest
package kv

import java.io.File

import cats.{ Eq, Show }
import cats.effect._
import cats.effect.concurrent.Ref
import io.circe.{ Codec, Decoder, Encoder }
import io.circe.syntax._
import org.scalacheck.{ Gen, Prop }
import org.specs2.Specification
import org.specs2.ScalaCheck
import org.specs2.execute.Result
import org.specs2.scalacheck.Parameters
import org.specs2.specification.core.SpecStructure
import raft.proptest.checker._
import raft.proptest.cluster._
import raft.proptest.kv.KVOps.{ KVEvent, KVMap }

import scala.concurrent.ExecutionContext.global
import scala.concurrent.TimeoutException
import scala.concurrent.duration._

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
      KVStore should be linearizable - $testSuccessfulRun
      """

  private implicit val cs: ContextShift[IO]       = IO.contextShift(global)
  private implicit val timer: Timer[IO]           = IO.timer(global)
  private implicit val kvEq: Eq[KVResult[String]] = Eq.fromUniversalEquals[KVResult[String]]

  private implicit val opsGen: Gen[List[KVOps]] = KVOps.gen(30)
  private implicit val multithreadedOps: Gen[List[(String, List[KVOps])]] = for {
    ops1 <- opsGen
    ops2 <- opsGen
  } yield {
    List("001" -> ops1, "002" -> ops2)
  }
  private implicit val strCodec = Codec.from(Decoder[String], Encoder[String])
  private val parFactor         = 30

  private val stateMachines = List("0", "1", "2")
    .traverse { id =>
      for {
        ref <- Ref[IO].of(Map.empty[String, String])
      } yield (id, KVOps.stateMachine(ref))
    }
    .map(_.toMap)

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
      val opsPerThread: List[(String, List[KVOps])] = multithreadedOps.sample.get

      for {
        results     <- clusterResource.use(executeKVOps(opsPerThread))
        checkResult <- computeLinearizability(parId, results)
      } yield checkResult

    }
    assertAllLinearizable(results.unsafeRunSync())

  }

  private def computeLinearizability(
    parId: Int,
    results: List[FullOperation[KVOps, KVResult[String]]]
  ): IO[LinearizedRes[FullOperation[KVOps, KVResult[String]]]] = {
    val history = History.fromList(results)
    IO {
      LinearizationCheck
        .betterWingAndGong(
          history,
          KVOps.model,
          Map.empty[String, String]
        )
    }.time(s"Linearization Check of $parId")
      .timeout(20.seconds)
      .recoverWith {
        case err: TimeoutException =>
          toFile(new File(s"core/bin/hist-${parId}.json"))(results.asJson) *> IO.raiseError(err)
      }
  }

  private val startCluster: IO[ClusterApi[IO, KVOps, KVResult[String]]] = {
    for {
      pairs        <- stateMachines
      clusterState <- Ref[IO].of(ClusterState.init[IO])
      allNodes     <- setupCluster(pairs)
      clusterApi = ClusterApi(allNodes, clusterState)
      _ <- clusterApi.start.time("Start cluster")
    } yield clusterApi
  }

  private val clusterResource: Resource[IO, ClusterApi[IO, KVOps, KVResult[String]]] =
    Resource.make(startCluster)(_.stop.time("Stop cluster"))

  private def executeKVOps(opsPerThread: List[(String, List[KVOps])])(
    clusterApi: ClusterApi[IO, KVOps, KVResult[String]]
  ): IO[List[FullOperation[KVOps, KVResult[String]]]] = {
    opsPerThread.parFlatTraverse {
      case (threadId, ops) =>
        ops.traverse { op =>
          val apiRes = (op match {
            case Put(_, _) | Delete(_) =>
              clusterApi.write(op).map(Right(_): Either[Throwable, KVResult[String]])
            case Get(_) => clusterApi.read(op).map(Right(_): Either[Throwable, KVResult[String]])
          }).timeout(1.second).recover {
            case err: Throwable => Either.left[Throwable, KVResult[String]](err)
          }

          for {
            start <- Timer[IO].clock.realTime(MICROSECONDS)
            res   <- apiRes
            end   <- Timer[IO].clock.realTime(MICROSECONDS)
          } yield {
            FullOperation(threadId, op, res, start, end)
          }
        }
    }
  }

  private def assertAllLinearizable[A: Show](results: List[LinearizedRes[A]]): Result = {
    Result.forall(results) {
      case Linearizable(_) => success
      case NonLinearizable(longestAttempt, failed, exp) =>
        failure(s"""Failed to linearize, longestStreak = ${longestAttempt.show}
             |failed at $failed,
             |expect $exp as result""".stripMargin)
    }
  }

  private def replayHist = {
    val task = for {
      js     <- fromFile(new File(s"core/bin/hist-4.json"))
      events <- IO.fromEither(js.as[List[KVEvent]])
    } yield {
      val history = History.fromList[KVOps, KVResult[String]](events)
      LinearizationCheck
        .betterWingAndGong[KVOps, KVResult[String], KVMap](
          history,
          KVOps.model,
          Map.empty[String, String]
        )
    }
    task.unsafeRunSync() match {
      case Linearizable(_) => success
      case NonLinearizable(longestAttempt, failed, exp) =>
        failure(s"Failed to linearize, longestStreak = $longestAttempt, failed at $failed, expect $exp")
    }
  }
}
