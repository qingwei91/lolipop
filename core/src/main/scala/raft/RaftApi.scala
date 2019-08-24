package raft

import cats.{ Contravariant, Functor }
import raft.algebra.append.AppendRPCHandler
import raft.algebra.client.{ ClientRead, ClientWrite }
import raft.algebra.election.VoteRPCHandler
import raft.model._

trait RaftApi[F[_], Cmd, State]
    extends ClientWrite[F, Cmd]
    with ClientRead[F, State]
    with AppendRPCHandler[F, Cmd]
    with VoteRPCHandler[F]

object RaftApi {

  implicit def apiFunctor[F[_]: Functor, Cmd]: Functor[RaftApi[F, Cmd, ?]] = new Functor[RaftApi[F, Cmd, ?]] {
    override def map[A, B](fa: RaftApi[F, Cmd, A])(f: A => B): RaftApi[F, Cmd, B] = new RaftApi[F, Cmd, B] {
      override def read: F[ReadResponse[B]] = fa.read.map {
        case r: RedirectTo => r
        case NoLeader => NoLeader
        case Read(a) => Read(f(a))
      }

      override def requestVote(req: VoteRequest): F[VoteResponse] = fa.requestVote(req)

      override def write(cmd: Cmd): F[WriteResponse] = fa.write(cmd)

      override def requestAppend(req: AppendRequest[Cmd]): F[AppendResponse] = fa.requestAppend(req)
    }
  }

  implicit def apiContravariant[F[_], State]: Contravariant[RaftApi[F, ?, State]] =
    new Contravariant[RaftApi[F, ?, State]] {
      override def contramap[A, B](fa: RaftApi[F, A, State])(f: B => A): RaftApi[F, B, State] = {
        new RaftApi[F, B, State] {
          override def read: F[ReadResponse[State]] = fa.read

          override def requestVote(req: VoteRequest): F[VoteResponse] = fa.requestVote(req)

          override def write(cmd: B): F[WriteResponse] = fa.write(f(cmd))

          override def requestAppend(req: AppendRequest[B]): F[AppendResponse] = {
            val logs    = req.entries
            val updated = logs.map(l => l.copy(command = f(l.command)))
            fa.requestAppend(req.copy(entries = updated))
          }
        }
      }
    }

}
