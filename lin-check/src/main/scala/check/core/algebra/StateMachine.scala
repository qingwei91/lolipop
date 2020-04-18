package check.core.algebra

trait StateMachine[F[_], Req, Res] {
  def execute(req: Req): F[Res]
}

trait LocalStateMachine[F[_], Req, Res] { self =>
  def execute(req: Req): (Res, self.type)
}
