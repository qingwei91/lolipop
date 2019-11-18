package raft
package proptest
package kv

import cats._
import cats.effect.concurrent.Ref
import cats.effect.{ Concurrent, Timer }
import cats.kernel.Eq
import io.circe.{ Codec, Decoder, Encoder }
import io.circe.generic.auto._
import io.circe.syntax._
import org.scalacheck.Gen
import raft.algebra.StateMachine
import raft.algebra.client.{ ClientRead, ClientWrite }
import raft.model._

import scala.concurrent.duration._

sealed trait KVOps
case class Put(k: String, v: String) extends KVOps
case class Get(k: String) extends KVOps
case class Delete(del: String) extends KVOps

@SuppressWarnings(Array("org.wartremover.warts.All"))
object KVOps {
  type KVMap         = Map[String, String]
  type KVEvent       = Event[KVOps, KVResult[String]]
  type KVHist        = List[KVEvent]
  type KVRaft[F[_]]  = ClientWrite[F, KVOps, KVResult[String]] with ClientRead[F, KVOps, KVResult[String]]
  type Cluster[F[_]] = Map[String, KVRaft[F]]

  implicit val eqKVOps: Eq[KVOps] = Eq.fromUniversalEquals[KVOps]
  implicit val codec: Codec[KVOps] = Codec.from(
    List[Decoder[KVOps]](
      Decoder[Put].widen,
      Decoder[Get].widen,
      Decoder[Delete].widen
    ).reduce(_ or _),
    Encoder.instance {
      case p: Put => p.asJson
      case g: Get => g.asJson
      case d: Delete => d.asJson
    }
  )

  def stateTransition: Map[String, String] => KVOps => (KVMap, KVResult[String]) = { st =>
    {
      case Put(k, v) => st.updated(k, v) -> WriteOK(k, v)
      case Delete(k) => (st - k)         -> Deleted(k)
      case Get(k) => st                  -> ReadOK(st.get(k))
    }

  }

  def stateMachine[F[_]](ref: Ref[F, KVMap]): StateMachine[F, KVOps, KVResult[String]] = {
    StateMachine.fromRef(ref) { st => cmd =>
      stateTransition(st)(cmd)
    }
  }

  val model: Model[KVOps, KVResult[String], KVMap] = Model.from(stateTransition)

  private def loopOverApi[F[_]](
    api: KVRaft[F],
    fireRequest: KVRaft[F] => F[ClientResponse[KVResult[String]]],
    cluster: Cluster[F],
    sleepTime: FiniteDuration = 200.millis
  )(implicit F: MonadError[F, Throwable], t: Timer[F], con: Concurrent[F]): F[KVResult[String]] = {
    fireRequest(api).flatMap {
      case RedirectTo(nodeID) => loopOverApi(cluster(nodeID), fireRequest, cluster, sleepTime)
      case NoLeader => t.sleep(sleepTime) *> loopOverApi(api, fireRequest, cluster, sleepTime)
      case CommandCommitted(res) => res.pure[F]
    }
  }
  def execute[F[_]: Monad](
    ops: KVOps,
    cluster: Cluster[F],
    threadId: String,
    sleepTime: FiniteDuration = 200.millis,
    timeout: FiniteDuration   = 2.seconds
  )(implicit F: MonadError[F, Throwable], t: Timer[F], con: Concurrent[F]): F[KVHist] = {

    val execution: F[KVEvent] = ops match {
      case Put(_, _) | Delete(_) =>
        val (_, api) = cluster.head
        loopOverApi[F](api, api => api.write(ops), cluster, sleepTime)
          .map(res => Ret(threadId, res))

      case op @ Get(_) =>
        val (_, api) = cluster.head

        loopOverApi[F](api, api => api.staleRead(op), cluster, sleepTime)
          .map(res => Ret(threadId, res))
    }

    execution
      .timeout(timeout)
      .recover {
        case err: Throwable => Failed(threadId, err)
      }
      .map(ret => List(Invoke(threadId, ops), ret))
  }

  val keysGen: Gen[String] = Gen.oneOf("k1", "k2", "k3")
  val opsGen: Gen[KVOps] = for {
    k <- keysGen
    v <- Gen.alphaStr.map(_.take(6))
    op <- Gen.oneOf[KVOps](
           Put(k, v): KVOps,
           Get(k): KVOps,
           Delete(k): KVOps
         )
  } yield op

  def gen(n: Int = 30): Gen[List[KVOps]] = {
    Gen.listOfN(n, opsGen)
  }

  implicit def showCmd: Show[KVOps] = Show.show {
    case Get(k) => s"Read key $k"
    case Put(k, v) => s"Update $k=$v"
    case Delete(k) => s"Delete $k"
  }
}
