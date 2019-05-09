package org.http4s.client.finagle

import cats.effect._
import com.twitter.finagle.{http => FH}
import com.twitter.finagle.Service
import org.http4s.Uri
import org.http4s.client.Client
import org.http4s.finagle.Finagle

object FinagleClient {
  def apply[F[_]: ConcurrentEffect](service: Service[FH.Request, FH.Response], streaming: Boolean = true): Client[F] =
    Finagle.mkClient[F](service, streaming)

  def fromServiceFactory[F[_]: ConcurrentEffect](serviceFactory: Uri => Resource[F, Service[FH.Request, FH.Response]],
                                                 streaming: Boolean = true): Client[F] =
    Finagle.mkServiceFactoryClient(serviceFactory, streaming)
}
