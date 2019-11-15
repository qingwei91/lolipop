package raft
package proptest
package kv

import cats.Applicative

class KVDistributedModel[F[_]: Applicative] extends Model[F, KVOps[String], KVResult[String], Map[String, String]] {
  override def step(st: Map[String, String], op: KVOps[String]): F[(Map[String, String], KVResult[String])] =
    Applicative[F].pure {
      op match {
        case Put(k, v) => st.updated(k, v) -> WriteOK
        case Get(k) => st                  -> ReadOK(st.get(k))
        case Delete(k) => (st - k)         -> WriteOK
      }
    }
}
