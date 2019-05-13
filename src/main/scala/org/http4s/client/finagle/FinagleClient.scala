package org.http4s.client.finagle

import cats.data.Kleisli
import cats.implicits._
import cats.effect._
import cats.effect.concurrent.{Ref, Semaphore}
import cats.effect.interop.twitter.syntax._
import com.twitter.finagle.{Http, http => FH}
import com.twitter.finagle.Service
import org.http4s.Uri
import org.http4s.client.Client
import org.http4s.finagle.Finagle

object FinagleClient {
  type Svc = Service[FH.Request, FH.Response]
  type Key = (Uri.Scheme, Uri.Authority)

  def apply[F[_]: ConcurrentEffect](
    service: Service[FH.Request, FH.Response],
    streaming: Boolean = true
  ): Client[F] =
    Finagle.mkClient[F](service, streaming)

  def fromServiceFactory[F[_]: ConcurrentEffect](
    serviceFactory: Resource[F, Kleisli[F, Key, Svc]],
    streaming: Boolean = true
  ): Resource[F, Client[F]] =
    serviceFactory.map { f =>
      Finagle.mkServiceFactoryClient(f, streaming)
    }
}

object ClientFactory {
  import FinagleClient.{Key, Svc}

  def apply[F[_]](configured: Http.Client)(implicit F: Sync[F]): Kleisli[F, Key, Svc] = Kleisli {
    case (scheme, authority) =>
      val dest    = authority.renderString
      val isHttps = scheme == Uri.Scheme.https

      F.delay {
        val client = if (isHttps) {
          configured.withTls(dest)
        } else {
          configured
        }
        client.newService(dest, s"client-factory-$dest")
      }
  }

  def cached[F[_]](
    factory: Kleisli[F, Key, Svc]
  )(implicit F: ConcurrentEffect[F]): F[Resource[F, Kleisli[F, Key, Svc]]] = {
    // TODO find another way to achieve this
    def getOrCreate(sem: Semaphore[F], ref: Ref[F, Map[Key, Svc]]): Kleisli[F, Key, Svc] = {
      Kleisli { key: Key =>
        ref.get.flatMap { cache =>
          cache.get(key) match {
            case Some(a) => F.pure(a)
            case None =>
              sem.withPermit {
                ref.get.flatMap { cache0 =>
                  cache0.get(key) match {
                    case Some(a) => F.pure(a)
                    case None =>
                      factory.run(key).flatMap { svc =>
                        ref.set(cache0.updated(key, svc)).as(svc)
                      }
                  }
                }
              }
          }
        }
      }
    }

    def closeAll(sem: Semaphore[F], ref: Ref[F, Map[Key, Svc]]): F[Unit] = {
      sem.withPermit {
        ref.get
          .map(_.values.toVector)
          .flatMap(_.traverse(svc => F.delay(svc.close()).fromFuture)) >> ref.set(Map.empty)
      }
    }

    for {
      sem      <- Semaphore.uncancelable(1)
      ref      <- Ref.of(Map.empty[Key, Svc])
      f        = getOrCreate(sem, ref)
      destroy  = closeAll(sem, ref)
      resource = f -> destroy
    } yield Resource(resource.pure[F])
  }
}
