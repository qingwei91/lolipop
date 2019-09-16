package raft
package proptest
package kv

import cats._
import cats.effect.concurrent.Ref
import cats.effect.{ Concurrent, Timer }
import cats.kernel.Eq
import org.scalacheck.Gen
import raft.algebra.StateMachine
import raft.algebra.client.{ ClientRead, ClientWrite }
import raft.model._

import scala.concurrent.duration._

sealed trait KVOps[V]
case class Put[V](k: String, v: V) extends KVOps[V]
case class Get[V](k: String) extends KVOps[V]
case class Delete[V](k: String) extends KVOps[V]

@SuppressWarnings(Array("org.wartremover.warts.All"))
object KVOps {
  type InnerSt       = Map[String, String]
  type KVCmd         = KVOps[String]
  type KVEvent       = Event[KVCmd, KVResult[String]]
  type KVHist        = List[KVEvent]
  type KVRaft[F[_]]  = ClientWrite[F, KVCmd] with ClientRead[F, InnerSt]
  type Cluster[F[_]] = Map[String, KVRaft[F]]

  implicit val eqKVCmd: Eq[KVCmd] = Eq.fromUniversalEquals[KVCmd]

  def stateMachine[F[_]](ref: Ref[F, InnerSt]): StateMachine[F, KVCmd, InnerSt] =
    StateMachine.fromRef(ref) { st =>
      {
        case Put(k, v) => st.updated(k, v)
        case Delete(k) => st - k
        case Get(_) => st
      }
    }

  def execute[F[_]: Monad](
    ops: KVCmd,
    cluster: Cluster[F],
    threadId: String,
    sleepTime: FiniteDuration = 200.millis,
    timeout: FiniteDuration   = 2.seconds
  )(implicit F: MonadError[F, Throwable], t: Timer[F], con: Concurrent[F]): F[KVHist] =
    ops match {
      case op @ Put(_, _) =>
        def loop(api: KVRaft[F]): F[Unit] = {
          api.write(op).flatMap {
            case RedirectTo(nodeID) => loop(cluster(nodeID))
            case NoLeader => t.sleep(sleepTime) *> loop(api)
            case CommandCommitted => Monad[F].unit
          }
        }
        val (_, api) = cluster.head
        val putTask = loop(api)
          .timeout(timeout)
          .as[KVEvent](Ret(threadId, WriteOK))
          .recover {
            case o: Throwable => Failure(threadId, o)
          }

        putTask.map { res =>
          List(Invoke(threadId, op), res)
        }
      case op @ Get(k) =>
        def loop(api: KVRaft[F]): F[InnerSt] = {
          api.read.flatMap {
            case RedirectTo(nodeID) => loop(cluster(nodeID))
            case NoLeader => t.sleep(sleepTime) *> loop(api)
            case Read(v) => F.pure(v)
          }
        }
        val (_, api) = cluster.head
        val getTask = loop(api)
          .timeout(timeout)
          .map[KVEvent](st => Ret(threadId, ReadOK(st.get(k))))
          .recover {
            case o: Throwable => Failure(threadId, o)
          }

        getTask.map { res =>
          List(
            Invoke(threadId, op),
            res
          )
        }
      case Delete(_) => Monad[F].pure(Nil)
    }
  val keysGen: Gen[String] = Gen.oneOf("k1", "k2", "k3")
  val opsGen: Gen[KVOps[String]] = for {
    k <- keysGen
    v <- Gen.alphaStr
    op <- Gen.oneOf[KVCmd](
           Put(k, v): KVCmd,
           Get(k): KVCmd,
           Delete(k): KVCmd
         )
  } yield op

  implicit val gen: Gen[List[KVOps[String]]] = {
    Gen.listOfN(30, opsGen)
  }
}
