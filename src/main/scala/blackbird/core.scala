package blackbird

import cats.data.Kleisli
import cats.effect.{ConcurrentEffect, Resource, Sync}
import com.twitter.finagle.{Http, Service}
import org.http4s.Uri
import org.http4s.client.Client

object FinagleClient {
  def apply[F[_]: ConcurrentEffect](
    service: Service[FH.Request, FH.Response],
    streaming: Boolean = true
  ): Client[F] =
    Finagle.mkClient[F](service, streaming)

  def fromServiceFactory[F[_]: ConcurrentEffect](
    serviceFactory: Resource[F, Factory[F]],
    streaming: Boolean = true
  ): Resource[F, Client[F]] =
    serviceFactory.map { factory =>
      Finagle.mkServiceFactoryClient(factory, streaming)
    }
}

object ClientFactory {
  def apply[F[_]](configured: Http.Client)(implicit F: Sync[F]): Factory[F] =
    Kleisli {
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

  def cachedFactory[F[_]](
    factory: Factory[F]
  )(implicit F: ConcurrentEffect[F]): F[Resource[F, Factory[F]]] = {
    def access(sem: Semaphore[F], ref: Ref[F, Map[ClientKey, HttpService]]): Kleisli[F, ClientKey, HttpService] = {
      def get(key: ClientKey): F[Option[HttpService]] = ref.get.map(_.get(key))
      def create(key: ClientKey): F[HttpService] = {
        for {
          svc <- factory(key)
          _   <- ref.getAndUpdate(_.updated(key, svc))
        } yield svc
      }

      def getOrCreate(key: ClientKey): F[HttpService] =
        sem.withPermit {
          get(key).flatMap {
            _.fold(create(key))(_.pure[F])
          }
        }

      Kleisli { key: ClientKey =>
        get(key).flatMap {
          _.fold(getOrCreate(key))(_.pure[F])
        }
      }
    }

    def closeAll(sem: Semaphore[F], ref: Ref[F, Map[ClientKey, HttpService]]): F[Unit] =
      sem.withPermit {
        ref.get
          .map(_.values.toVector)
          .flatMap(_.traverse(svc => F.delay(svc.close()).fromFuture)) >> ref.set(Map.empty)
      }

    for {
      sem     <- Semaphore.uncancelable(1)
      ref     <- Ref.of(Map.empty[ClientKey, HttpService])
      f       = access(sem, ref)
      destroy = closeAll(sem, ref)
    } yield Resource((f, destroy).pure[F])
  }
}
