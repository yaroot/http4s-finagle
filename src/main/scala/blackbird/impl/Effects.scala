package blackbird.impl

import cats.effect._
import cats.implicits._
import com.twitter.util._

object Effects {
  def fromFuture[F[_], A](f: F[Future[A]])(implicit F: ConcurrentEffect[F]): F[A] = {
    f.flatMap { future =>
      future.poll match {
        case Some(Return(a)) => F.pure(a)
        case Some(Throw(e))  => F.raiseError(e)
        case None =>
          F.cancelable { cb =>
            val _ = future.respond {
              case Return(a) => cb(a.asRight)
              case Throw(e)  => cb(e.asLeft)
            }

            F.uncancelable(F.delay(future.raise(new FutureCancelledException)))
          }
      }
    }
  }

  def unsafeRunAsync[F[_], A](f: F[A])(implicit F: ConcurrentEffect[F]): Future[A] = {
    val p = Promise[A]()

    (F.runCancelable(f) _)
      .andThen(_.map { cancel =>
        p.setInterruptHandler {
          case ex =>
            p.updateIfEmpty(Throw(ex))
            F.toIO(cancel).unsafeRunAsyncAndForget()
        }
      })(e => IO.delay(p.updateIfEmpty(e.fold(Throw(_), Return(_)))))

    p
  }
}