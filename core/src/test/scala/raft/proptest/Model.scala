package raft
package proptest

/*
  Model does not encapsulate state, this is to allow
  caller (checker specifically) to be able to
  reuse the state

  We cannot reuse StateMachine as Model under test because
  StateMachine encapsulate state, meaning there's no way to rewind an
  action on StateMachine, which is crucial for Linearization model

  Another consequence of such design is that Model is sequential but
  StateMachine can be concurrent
 */
trait Model[Op, Res, St] {
  def step(st: St, op: Op): (St, Res)
}

object Model {
  def from[Op, Res, St](f: St => Op => (St, Res)): Model[Op, Res, St] = new Model[Op, Res, St] {
    override def step(st: St, op: Op): (St, Res) = {
      f(st)(op)
    }
  }
}
