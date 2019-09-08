package raft.proptest.kv

import cats.effect._
import cats.effect.concurrent.Ref
import cats.instances.list._
import cats.syntax.traverse._
import org.specs2.Specification
import org.specs2.specification.core.SpecStructure
import scala.concurrent.duration._
import scala.concurrent.ExecutionContext.global

@SuppressWarnings(Array("org.wartremover.warts.Any"))
class RandomTest extends Specification {
  override def is: SpecStructure =
    s2"""
      KVStore should be linearizable - $testRun
      """

  implicit val cs: ContextShift[IO] = IO.contextShift(global)
  implicit val timer: Timer[IO]     = IO.timer(global)

  def testRun = {
    // 3 nodes test
    val idStateMachines = List("0", "1", "2")
      .traverse { id =>
        for {
          ref <- Ref[IO].of(Map.empty[String, String])
        } yield (id, KVOps.stateMachine(ref))
      }
      .map(_.toMap)

    val testActions = List[KVOps[String]](
      Put("A", "20"),
      Put("A", "30"),
      Get("A")
    )

    val program = for {
      pairs   <- idStateMachines
      cluster <- KVOps.setupCluster(pairs)
      _       <- timer.sleep(10.seconds) // why is it slow to get leader?
      client = KVOps.client[IO](cluster.mapValues(_.api), 300.millis, 3.seconds)
      results <- testActions.traverse(op => KVOps.execute(op, client, "thread1"))
    } yield {
      results.flatMap(hs => hs)
    }

    program.unsafeRunTimed(15.seconds) must not be_=== None
  }
}
