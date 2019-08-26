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
import raft.algebra.membership.MembershipStateMachine
import raft.model._
import raft.setup._

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

  import RaftApi.apiContravariant
  import RaftProcess.deriveContravariant

  // for some reason compiler failed to derive this instance
  implicit val contra: Contravariant[RaftProcess[IO, ?, Map[String, String]]] = deriveContravariant

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
      cluster  <- prepareRaft[IO](List("1", "2", "3"), CommutativeApplicative[IO])
      internal <- Ref[IO].of(InternalState(Map.empty, Set.empty))
      refined = cluster.mapValues { proc =>
        val mapped = proc.map(_._1)
        contra.contramap[Cmd, RegistryCmd](mapped)(Left(_))
      }
      exe <- program.foldMap(toIO(refined, internal))
    } yield exe
  }

}

@SuppressWarnings(Array("org.wartremover.warts.Any"))
object RegistrySpec {
  type Cmd   = Either[RegistryCmd, AddMemberRequest]
  type State = (Map[String, String], ClusterMembership)

  implicit val showCmd: Show[Cmd]                                        = Show.fromToString[Cmd]
  implicit val cmdEq: Eq[Cmd]                                            = Eq.fromUniversalEquals[Cmd]
  implicit val showState: Show[(Map[String, String], ClusterMembership)] = Show.fromToString[State]

  case class Stuff[F[_]](
    sm: StateMachine[F, Cmd, State],
    nodeS: RaftNodeState[F, Cmd],
    append: AppendRPCHandler[F, Cmd],
    vote: VoteRPCHandler[F],
    logger: EventsLogger[F, Cmd, State]
  )

  def stateMachineWithMembership[F[_]: Sync, C, S](
    nodeId: String,
    stateMachine: StateMachine[F, C, S]
  ): F[StateMachine[F, Either[C, AddMemberRequest], (S, ClusterMembership)]] = {
    for {
      membershipRef <- Ref[F].of(ClusterMembership(nodeId, Set.empty))
    } yield {
      val membershipStateMachine = new MembershipStateMachine(membershipRef)
      StateMachine.compose(stateMachine, membershipStateMachine)
    }
  }
  def prepareHandlers[F[_]: Sync: Concurrent](id: String)(
    implicit tm: Timer[F]
  ): F[Stuff[F]] = {
    for {
      refMap           <- Ref[F].of(Map.empty[String, String])
      refLog           <- Ref[F].of(Seq.empty[RaftLog[Either[RegistryCmd, AddMemberRequest]]])
      refMetadata      <- Ref[F].of(Metadata(0, None))
      fullStateMachine <- stateMachineWithMembership(id, RegistryMachine(refMap))

      logsIO     = new TestLogsIO[F, Cmd](refLog)
      metadataIO = new TestMetadata[F](refMetadata)
      logger     = new Slf4jLogger[F, Cmd, State]
      state <- RaftNodeState.init[F, Cmd](id, metadataIO, logsIO)
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

  implicit val stateProjection: State => ClusterMembership = _._2

  def prepareRaft[F[_]: Sync: Concurrent](ids: List[String], comm: CommutativeApplicative[F])(
    implicit cs: ContextShift[F],
    tm: Timer[F]
  ): F[Map[String, RaftProcess[F, Cmd, State]]] = {

    for {
      handlers <- ids.traverse(id => prepareHandlers[F](id).map(id -> _))
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
