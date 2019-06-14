package raft

import java.io.{ File, FileWriter, PrintWriter }
import java.util.concurrent.Executors

import cats.data.NonEmptyList
import cats.effect.concurrent.Ref
import cats.effect.{ ContextShift, IO, Timer }
import io.circe.Json
import org.specs2.Specification
import org.specs2.specification.core.SpecStructure
import raft.RaftReplicationSpec.{ managedProcesses, timeToReplication }
import raft.algebra.event.EventLogger
import raft.debug.JsonEventLogger
import raft.setup.TestClient

import scala.concurrent.ExecutionContext

@SuppressWarnings(Array("org.wartremover.warts.All"))
class Testbed extends Specification {
  override def is: SpecStructure =
    s2"""
        $test
      """
  def test = {
    val executor                        = Executors.newFixedThreadPool(4)
    val ecToUse                         = ExecutionContext.fromExecutor(executor)
    implicit val ioCS: ContextShift[IO] = IO.contextShift(ecToUse)
    implicit val ioTM: Timer[IO]        = IO.timer(ecToUse)

    val jsonMapRef: Ref[IO, Map[String, Seq[Json]]] = Ref.unsafe(Map.empty)

    def jsonLogger(id: String): EventLogger[IO, String, String] = {
      new JsonEventLogger[IO, String, String](str => {
        for {
          jsonMap <- jsonMapRef.get
          json    = jsonMap.getOrElse(id, Seq.empty[Json])
          updated = json :+ str
          _ <- jsonMapRef.update(_.updated(id, updated))
        } yield ()
      })
    }

    val deps = new RaftTestDeps[IO](jsonLogger)
    import deps._

    val allResults = managedProcesses(tasksIO).use { raftComponents =>
      val clients = raftComponents.map { components =>
        components.state.config.nodeId -> components.api
      }.toNem

      val commands = NonEmptyList.fromListUnsafe((0 to 20).map(i => s"Cmd$i").toList)

      val writeRequests = commands.parTraverse { cmd =>
        TestClient.writeToLeader(clients.toSortedMap)("0", cmd)
      }

      for {
        _        <- ioTM.sleep(timeToReplication) // allow time for election to avoid contention
        _        <- writeRequests.timeout(timeToReplication * 2)
        jsonLogs <- jsonMapRef.get
      } yield {
        jsonLogs.map {
          case (id, logs) =>
            new PrintWriter(new FileWriter(new File(s"events-$id.json"))).println(
              Json.arr(logs: _*).spaces2
            )
        }
        success
      }

    }

    try {
      allResults.unsafeRunSync()
    } catch {
      case t: Throwable => failure(s"Unexpected failure ${t.getMessage}")
    } finally {
      executor.shutdown()
    }
  }

}
