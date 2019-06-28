package raft

import cats.data._
import cats.effect.concurrent.{ MVar, Ref }
import cats.effect._
import fs2.concurrent.{ Queue, Topic }
import raft.algebra.append._
import raft.algebra.election._
import raft.algebra.event.{ EventLogger, InMemEventLogger }
import raft.model._
import raft.setup._

import scala.concurrent.duration._

@SuppressWarnings(Array("org.wartremover.warts.All"))
class RaftTestDeps[F[_]](
  logger: String => EventLogger[F, String, String],
  shouldFail: (String, String) => Boolean = (_, _) => false,
)(
  implicit cs: ContextShift[F],
  tm: Timer[F],
  con: Concurrent[F]
) {

  private val clientIds = {
    NonEmptySet.of(0, 1 to 4: _*).map(_.toString)
  }

  val tasksIO: F[NonEmptyList[RaftTestComponents[F]]] = clientIds.toNonEmptyList
    .traverse { i =>
      for {
        time <- tm.clock.realTime(MILLISECONDS)
        tpe = Follower(0, 0, time, None)
        committedStream <- Topic[F, String]("")
        clientReqQueue  <- Queue.bounded[F, F[Unit]](100)
        persist         <- Ref.of[F, Metadata](Metadata.init)
        servTpe         <- Ref.of[F, ServerType](tpe)
        lock            <- MVar[F].of(())
        baseLog         <- Ref.of[F, Seq[RaftLog[String]]](Seq.empty)
        testState       <- Ref[F].of("")
      } yield {
        val clusterConf  = ClusterConfig(i, clientIds - i)
        val stateMachine = new TestStateMachine[F](testState)
        val state = TestState(
          clusterConf,
          new TestMetadata(persist),
          servTpe,
          lock,
          new TestLogsIO(baseLog)
        )

        val eventLogger = logger(i)
        val allState    = AllState(stateMachine, state, committedStream, clientReqQueue)
        val append = new AppendRPCHandlerImpl(
          stateMachine,
          state,
          eventLogger
        )
        val vote = new VoteRPCHandlerImpl(state, eventLogger)
        (i, vote, allState, append, eventLogger)
      }
    }
    .flatMap { data =>
      val appendResponders = data.map { case (i, _, _, append, _) => i -> append }.toNem
      val voteResponders   = data.map { case (i, vote, _, _, _) => i   -> vote }.toNem

      val network = new UnreliableNetwork(
        new InMemNetwork(appendResponders.toSortedMap, voteResponders.toSortedMap),
        shouldFail
      )

      data.traverse {
        case (_, voteHandler, AllState(stateMachine, allState, _, _), appendHandler, eventLogger) =>
          for {
            proc <- RaftProcess(
                     stateMachine,
                     allState,
                     network,
                     appendHandler,
                     voteHandler,
                     eventLogger
                   )
          } yield {
            RaftTestComponents(proc, allState, eventLogger)
          }
      }
    }
}

object RaftTestDeps {
  def apply[F[_]](
    implicit cs: ContextShift[F],
    tm: Timer[F],
    con: Concurrent[F]
  ): RaftTestDeps[F] = {
    val buffer = Ref.unsafe[F, StringBuffer](new StringBuffer(10000))
    new RaftTestDeps[F](logger = i => new InMemEventLogger[F, String, String](i, buffer))
  }

  def apply[F[_]](shouldFail: (String, String) => Boolean)(
    implicit cs: ContextShift[F],
    tm: Timer[F],
    con: Concurrent[F]
  ): RaftTestDeps[F] = {
    val buffer = Ref.unsafe[F, StringBuffer](new StringBuffer(10000))
    new RaftTestDeps[F](i => new InMemEventLogger[F, String, String](i, buffer), shouldFail)
  }

}
