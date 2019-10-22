import cats.data._
import cats.free._
import cats._
import cats.arrow.FunctionK
import cats.implicits._

sealed trait NumOp[A]
case class Add[A](i: Int) extends NumOp[A]

implicit val func: Functor[NumOp] = new Functor[NumOp] {
  override def map[A, B](fa: NumOp[A])(f: A => B): NumOp[B] = {
    fa match {
      case Add(i) => Add(i)
    }
  }
}

val add: Free[NumOp, Unit] = Free.liftF(Add(10))

add.map(_ => add).flatten

val prog = for {
  _ <- add
  _ <- add
} yield ()

type IntFn[A] = Kleisli[Id, Int, Int]

val doAdd = new FunctionK[NumOp, IntFn] {
  override def apply[A](fa: NumOp[A]): IntFn[A] = {
    fa match {
      case Add(i) => Kleisli[Id, Int, Int](x => x + i)
    }
  }
}

val addFn = prog.foldMap(doAdd)

println(addFn.run(20))
