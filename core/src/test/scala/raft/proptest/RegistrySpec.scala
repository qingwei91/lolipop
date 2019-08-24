package raft
package proptest

import cats.{ Contravariant, Show }
import cats.effect.{ ContextShift, IO, Sync, Timer }
import cats.effect.concurrent.Ref
import cats.kernel.Eq
import org.specs2.Specification
import org.specs2.specification.core.SpecStructure
import raft.algebra.StateMachine
import raft.algebra.append.{ AppendRPCHandler, AppendRPCHandlerImpl }
import raft.algebra.election.{ VoteRPCHandler, VoteRPCHandlerImpl }
import raft.algebra.membership.MembershipStateMachine
import raft.model._
import raft.setup._

import scala.concurrent.ExecutionContext.global

@SuppressWarnings(Array("org.wartremover.warts.Any"))
class RegistrySpec extends Specification {
  override def is: SpecStructure =
    s2"""

      """

  import RegistrySpec._
  import RegistryActionF._

  implicit val IOCs    = IO.contextShift(global)
  implicit val IOTimer = IO.timer(global)

  import RaftApi.apiContravariant
  import RaftProcess.deriveContravariant

  implicit val contra: Contravariant[RaftProcess[IO, ?, Map[String, String]]] = deriveContravariant

  def simpleTest = {
    val program = for {
      _ <- startAll
      _ <- putKV("Hello", "world")
      _ <- stopAll
    } yield ()

    for {
      cluster  <- prepareRaft(List("1", "2", "3"))
      internal <- Ref[IO].of(InternalState(Map.empty, Set.empty))
      refined = cluster.mapValues { proc =>
        val mapped: RaftProcess[IO, Cmd, Map[String, String]] = proc.map(_._1)

        contra.contramap[Cmd, RegistryCmd](mapped)(Left(_))
      }
      exe <- program.foldMap(compile(refined, internal))
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

  case class Stuff(
    sm: StateMachine[IO, Cmd, State],
    nodeS: RaftNodeState[IO, Cmd],
    append: AppendRPCHandler[IO, Cmd],
    vote: VoteRPCHandler[IO],
    logger: InMemEventsLogger[IO, Cmd, State]
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
  def prepareHandlers(id: String)(
    implicit cs: ContextShift[IO],
    tm: Timer[IO]
  ): IO[Stuff] = {
    for {
      refMap           <- Ref[IO].of(Map.empty[String, String])
      refLog           <- Ref[IO].of(Seq.empty[RaftLog[Either[RegistryCmd, AddMemberRequest]]])
      refMetadata      <- Ref[IO].of(Metadata(0, None))
      refStrBuf        <- Ref[IO].of(new StringBuffer(10000))
      fullStateMachine <- stateMachineWithMembership(id, RegistryMachine(refMap))

      logsIO     = new TestLogsIO[IO, Cmd](refLog)
      metadataIO = new TestMetadata[IO](refMetadata)
      logger     = new InMemEventsLogger[IO, Cmd, State](id, refStrBuf)
      state <- RaftNodeState.init[IO, Cmd](id, metadataIO, logsIO)
      appendHandler = new AppendRPCHandlerImpl(
        fullStateMachine,
        state,
        logger
      ): AppendRPCHandler[IO, Cmd]
      voteHandler = new VoteRPCHandlerImpl(state, logger): VoteRPCHandler[IO]
    } yield {
      Stuff(fullStateMachine, state, appendHandler, voteHandler, logger)
    }
  }

  implicit val stateProjection: State => ClusterMembership = _._2

  def prepareRaft(ids: List[String])(
    implicit cs: ContextShift[IO],
    tm: Timer[IO]
  ): IO[Map[String, RaftProcess[IO, Cmd, State]]] = {

    for {
      handlers <- ids.traverse(id => prepareHandlers(id).map(id -> _))
      handlersMap = handlers.toMap
      appends     = handlersMap.mapValues(_.append)
      votes       = handlersMap.mapValues(_.vote)
      network     = new InMemNetwork[IO, Cmd, State](appends, votes)
      cluster <- handlersMap.unorderedTraverse { stuff =>
                  RaftProcess(
                    stuff.sm,
                    stuff.nodeS,
                    network,
                    stuff.append,
                    stuff.vote,
                    stuff.logger
                  )
                }
    } yield {
      cluster
    }
  }
}
