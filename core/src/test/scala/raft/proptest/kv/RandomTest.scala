package raft
package proptest
package kv

import java.io.File

import cats.Eq
import cats.effect._
import cats.effect.concurrent.Ref
import io.circe.{ Codec, Decoder, Encoder }
import io.circe.syntax._
import org.scalacheck.Gen
import org.specs2.Specification
import org.specs2.execute.Result
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
class RandomTest extends Specification {
  override def is: SpecStructure =
    s2"""
      KVStore should be linearizable - $replayHist
      """

  private implicit val cs: ContextShift[IO]       = IO.contextShift(global)
  private implicit val timer: Timer[IO]           = IO.timer(global)
  private implicit val kvEq: Eq[KVResult[String]] = Eq.fromUniversalEquals[KVResult[String]]

  private implicit val opsGen: Gen[List[KVOps]] = KVOps.gen(30)
  private implicit val strCodec                 = Codec.from(Decoder[String], Encoder[String])
  private val parFactor                         = 10

  private def testRun = {
    val idStateMachines = List("0", "1", "2")
      .traverse { id =>
        for {
          ref <- Ref[IO].of(Map.empty[String, String])
        } yield (id, KVOps.stateMachine(ref))
      }
      .map(_.toMap)

    def multiThreadRun(opsPerThread: List[(String, List[KVOps])]): IO[List[KVEvent]] = {
      for {
        pairs <- idStateMachines
        clusterState <- Ref[IO].of(
                         ClusterState[IO](
                           Set.empty,
                           Set.empty,
                           Map.empty
                         )
                       )
        allNodes <- setupCluster(pairs)
        clusterOps = ClusterManager(allNodes, clusterState)
        _ <- clusterOps.start.time("Start cluster")
        results <- opsPerThread.parFlatTraverse {
                    case (threadId, ops) =>
                      ops.traverse(
                        op =>
                          KVOps
                            .execute[IO](
                              ops       = op,
                              cluster   = allNodes.mapValues(_.api),
                              threadId  = threadId,
                              sleepTime = 300.millis,
                              timeout   = 3.seconds
                          )
                      )
                  }
        _ <- clusterOps.stop.time("Stop cluster")
      } yield {
        results.flatten
      }
    }

    val results = parTest(parFactor) { parId =>
      val ops1 = opsGen.sample.get
      val ops2 = opsGen.sample.get
      multiThreadRun(List("001" -> ops1, "002" -> ops2))
        .flatMap { combined =>
          val history = History.fromList[KVOps, KVResult[String]](combined)

          LinearizationCheck
            .wingAndGongUnsafe[IO, KVOps, KVResult[String], KVMap](history, KVOps.model, Map.empty[String, String])
            .time(s"Linearization Check of $parId")
            .timeout(20.seconds)
            .recoverWith {
              case err: TimeoutException =>
                toFile(new File(s"core/bin/hist-${parId}.json"))(combined.asJson) *>
                  IO(println(s"Failed to finish Check in time for ${ops1} and ${ops2}")) *> IO.raiseError(err)
            }
        }
    }.unsafeRunTimed(30.seconds).get

    Result.forall(results) {
      case Linearizable(_) => success
      case NonLinearizable(longestAttempt, failed, exp, act) =>
        failure(s"Failed to linearize, longestStreak = $longestAttempt, failed at $failed, expect $exp, got $act")
    }
  }

  private def replayHist = {
    val task = for {
      js     <- fromFile(new File(s"core/bin/hist-4.json"))
      events <- IO.fromEither(js.as[List[KVEvent]])
    } yield {
      val history = History.fromList[KVOps, KVResult[String]](events)
      LinearizationCheck
        .wingAndGongUnsafeNew[KVOps, KVResult[String], KVMap](
          history,
          KVOps.model,
          Map.empty[String, String]
        )
    }
    task.unsafeRunSync() match {
      case Linearizable(_) => success
      case NonLinearizable(longestAttempt, failed, exp, act) =>
        failure(s"Failed to linearize, longestStreak = $longestAttempt, failed at $failed, expect $exp, got $act")
    }
  }
}
