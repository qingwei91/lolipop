package raft
package proptest

import cats._
import cats.effect._
import cats.effect.concurrent.Ref
import cats.kernel.Eq
import org.specs2.Specification
import org.specs2.specification.core.SpecStructure
import raft.algebra.StateMachine
import raft.algebra.append.{ AppendRPCHandler, AppendRPCHandlerImpl }
import raft.algebra.election.{ VoteRPCHandler, VoteRPCHandlerImpl }
import raft.algebra.event.EventsLogger
import raft.model._
import raft.setup._
import raft.util.Slf4jLogger

import scala.concurrent.duration._
import scala.concurrent.ExecutionContext.global

@SuppressWarnings(Array("org.wartremover.warts.Any"))
class RegistrySpec extends Specification {
  override def is: SpecStructure =
    s2"""
      $simpleTest
      """

  import RegistrySpec._
  import RegistryActionF._

  implicit val IOCs    = IO.contextShift(global)
  implicit val IOTimer = IO.timer(global)

  def simpleTest = {
    val program = for {
      _ <- startAll
      _ <- putKV("Hello", "world")
      _ <- stopAll
    } yield ()

    runProgram(program).unsafeRunTimed(10.seconds) must_=== Some(())
  }

  def runProgram[A](program: RegistryProgram[A]): IO[A] = {
    for {
      cluster  <- prepareRaft[IO](Set("1", "2", "3"), CommutativeApplicative[IO])
      internal <- Ref[IO].of(InternalState(Map.empty, Set.empty))
      exe      <- program.foldMap(toIO(cluster, internal))
    } yield exe
  }

}

@SuppressWarnings(Array("org.wartremover.warts.Any"))
object RegistrySpec {
  type Cmd   = RegistryCmd
  type State = Map[String, String]

  implicit val showCmd: Show[Cmd]                   = Show.fromToString[Cmd]
  implicit val cmdEq: Eq[Cmd]                       = Eq.fromUniversalEquals[Cmd]
  implicit val showState: Show[Map[String, String]] = Show.fromToString[State]

  case class Stuff[F[_]](
    sm: StateMachine[F, Cmd, State],
    nodeS: RaftNodeState[F, Cmd],
    append: AppendRPCHandler[F, Cmd],
    vote: VoteRPCHandler[F],
    logger: EventsLogger[F, Cmd, State]
  )

  def prepareHandlers[F[_]: Sync: Concurrent](id: String, peers: Set[String])(
    implicit tm: Timer[F]
  ): F[Stuff[F]] = {
    for {
      refMap      <- Ref[F].of(Map.empty[String, String])
      refLog      <- Ref[F].of(Seq.empty[RaftLog[RegistryCmd]])
      refMetadata <- Ref[F].of(Metadata(0, None))
      fullStateMachine = RegistryMachine(refMap)
      clusterConf      = ClusterConfig(id, peers)
      logsIO           = new TestLogsIO[F, Cmd](refLog)
      metadataIO       = new TestMetadata[F](refMetadata)
      logger           = new Slf4jLogger[F, Cmd, State](id)
      state <- RaftNodeState.init[F, Cmd](clusterConf, metadataIO, logsIO)
      appendHandler = new AppendRPCHandlerImpl(
        fullStateMachine,
        state,
        logger
      ): AppendRPCHandler[F, Cmd]
      voteHandler = new VoteRPCHandlerImpl(state, logger): VoteRPCHandler[F]
    } yield {
      Stuff[F](fullStateMachine, state, appendHandler, voteHandler, logger)
    }
  }

  def prepareRaft[F[_]: Sync: Concurrent](ids: Set[String], comm: CommutativeApplicative[F])(
    implicit cs: ContextShift[F],
    tm: Timer[F]
  ): F[Map[String, RaftProcess[F, Cmd, State]]] = {

    for {
      handlers <- ids.toList.traverse(id => prepareHandlers[F](id, ids - id).map(id -> _))
      handlersMap = handlers.toMap
      appends     = handlersMap.mapValues(_.append)
      votes       = handlersMap.mapValues(_.vote)
      network     = new InMemNetwork[F, Cmd, State](appends, votes)
      cluster <- handlersMap.unorderedTraverse[F, RaftProcess[F, Cmd, State]] { stuff =>
                  RaftProcess(
                    stuff.sm,
                    stuff.nodeS,
                    network,
                    stuff.append,
                    stuff.vote,
                    stuff.logger
                  )
                }(comm)
    } yield {
      cluster
    }
  }
}
