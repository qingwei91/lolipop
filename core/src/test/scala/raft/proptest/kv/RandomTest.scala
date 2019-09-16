package raft
package proptest
package kv

import cats.Eq
import cats.effect._
import cats.effect.concurrent.Ref
import checker.LinearizationCheck
import org.scalacheck.Prop
import org.specs2.{ ScalaCheck, Specification }
import org.specs2.specification.core.SpecStructure
import raft.proptest.cluster.ClusterOps.ClusterState
import raft.proptest.cluster.{ ClusterOps, Sleep, StartAll, StopAll }

import scala.concurrent.duration._
import scala.concurrent.ExecutionContext.global

@SuppressWarnings(
  Array(
    "org.wartremover.warts.Any",
    "org.wartremover.warts.Product",
    "org.wartremover.warts.Serializable"
  )
)
class RandomTest extends Specification with ScalaCheck {
  override def is: SpecStructure =
    s2"""
      KVStore should be linearizable - $testRun
      """

  implicit val cs: ContextShift[IO]       = IO.contextShift(global)
  implicit val timer: Timer[IO]           = IO.timer(global)
  implicit val kvEq: Eq[KVResult[String]] = Eq.fromUniversalEquals[KVResult[String]]

  import KVOps.gen

  def testRun = {
    val idStateMachines = List("0", "1", "2")
      .traverse { id =>
        for {
          ref <- Ref[IO].of(Map.empty[String, String])
        } yield (id, KVOps.stateMachine(ref))
      }
      .map(_.toMap)
    val testClusterAct = List(StartAll, Sleep(5.seconds), StopAll)
    val model          = new KVModel[IO]

    prop { ops: List[KVOps[String]] =>
      val test = for {
        pairs <- idStateMachines
        clusterState <- Ref[IO].of(
                         ClusterState[IO](
                           Set.empty,
                           Set.empty,
                           Map.empty
                         )
                       )
        allNodes <- setupCluster(pairs)
        _        <- timer.sleep(2.seconds) // why is it slow to get leader?
        clusterLifeCycleIO = testClusterAct.traverse(op => ClusterOps.execute(allNodes, clusterState)(op))
        testOpIO = ops.traverse(
          op =>
            KVOps
              .execute[IO](
                ops       = op,
                cluster   = allNodes.mapValues(_.api),
                threadId  = "thread1",
                sleepTime = 300.millis,
                timeout   = 3.seconds
            )
        )
        results <- (testOpIO, clusterLifeCycleIO).parMapN {
                    case (opsResult, _) => opsResult
                  }
        history = results.flatten
        results <- LinearizationCheck.analyse(history, model, Map.empty[String, String])
      } yield results
      test.unsafeRunTimed(15.seconds) must_=== Some(true)
    }
  }
}
