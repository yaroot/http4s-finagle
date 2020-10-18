package blackbird

import java.net.SocketAddress

import cats.data.Kleisli
import cats.effect._
import cats.effect.concurrent._
import cats.implicits._
import com.twitter.finagle.{Http, ListeningServer, Service, http => FH}
import org.http4s.{HttpApp, Uri}
import org.http4s.client.Client
import blackbird.impl._

object Blackbird {
  def apply[F[_]: ConcurrentEffect](
    service: Service[FH.Request, FH.Response],
    streaming: Boolean = true
  ): Client[F] =
    Impl.mkClient[F](service, streaming)

  def fromServiceFactory[F[_]: ConcurrentEffect](
    serviceFactory: Resource[F, ClientFactory[F]],
    streaming: Boolean = true
  ): Resource[F, Client[F]] =
    serviceFactory.map { factory =>
      Impl.mkServiceFactoryClient(factory, streaming)
    }

  def serveRoutes[F[_]: ConcurrentEffect](addr: String, app: HttpApp[F], server: Http.Server): ListeningServer =
    server.serve(addr, Impl.mkService[F](app, isStreaming(server)))

  def serveRoutes[F[_]: ConcurrentEffect](addr: SocketAddress, app: HttpApp[F], server: Http.Server): ListeningServer =
    server.serve(addr, Impl.mkService[F](app, isStreaming(server)))

  def isStreaming(server: Http.Server): Boolean = server.params[FH.param.Streaming].enabled
}

object ClientFactory {
  def apply[F[_]](configured: Http.Client)(implicit F: Sync[F]): ClientFactory[F] =
    Kleisli { case (scheme, authority) =>
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
    factory: ClientFactory[F]
  )(implicit F: ConcurrentEffect[F]): F[Resource[F, ClientFactory[F]]] = {
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
          .flatMap(
            _.traverse(svc => Effects.fromFuture(F.delay(svc.close())))
          ) >> ref.set(Map.empty)
      }

    for {
      sem    <- Semaphore.uncancelable(1)
      ref    <- Ref.of(Map.empty[ClientKey, HttpService])
      f       = access(sem, ref)
      destroy = closeAll(sem, ref)
    } yield Resource((f, destroy).pure[F])
  }
}
