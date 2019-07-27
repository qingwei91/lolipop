package raft.grpc

import cats.effect.Async
import raft.algebra.io.NetworkIO
import raft.grpc.command.Increment
import raft.grpc.server.rpc.PeerRPCGrpc.PeerRPCStub
import raft.grpc.server.rpc.{ AppendRequestProto, Log, VoteRequestProto }
import raft.model.{ AppendRequest, AppendResponse, VoteRequest, VoteResponse }

import scala.concurrent.ExecutionContext
import scala.util.{ Failure, Success }

class GrpcNetwork[F[_]: Async](clients: Map[String, PeerRPCStub])(implicit ec: ExecutionContext)
    extends NetworkIO[F, Increment] {
  override def sendAppendRequest(nodeID: String, appendReq: AppendRequest[Increment]): F[AppendResponse] = {
    import appendReq._

    Async[F].async { cb =>
      clients(nodeID)
        .append(
          AppendRequestProto(
            term,
            leaderId,
            prevLogIdx,
            prevLogTerm,
            entries.map { log =>
              Log(log.idx, log.term, log.command)
            },
            leaderCommit
          )
        )
        .onComplete {
          case Success(value) =>
            import value._
            val appendRes = AppendResponse(term, success)
            cb(Right(appendRes))
          case Failure(err) => cb(Left(err))
        }
    }

  }

  override def sendVoteRequest(nodeID: String, voteRq: VoteRequest): F[VoteResponse] = {
    import voteRq._
    Async[F].async { cb =>
      clients(nodeID).get(VoteRequestProto(term, candidateID, lastLogIdx, lastLogTerm)).onComplete {
        case Success(voteRes) =>
          val res = VoteResponse(voteRes.term, voteRes.granted)
          cb(Right(res))
        case Failure(err) => cb(Left(err))
      }
    }
  }
}
