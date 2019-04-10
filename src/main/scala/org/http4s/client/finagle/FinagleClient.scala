package org.http4s.client.finagle

import cats.effect._
import com.twitter.finagle.{http => FH}
import com.twitter.finagle.Service
import org.http4s.Uri
import org.http4s.client.Client
import org.http4s.finagle.Utils

object FinagleClient {
  def apply[M[_]: ConcurrentEffect](
      service: Service[FH.Request, FH.Response],
      streaming: Boolean = true): Client[M] =
    Utils.mkClient[M](service, streaming)

  def fromServiceFactory[M[_]: ConcurrentEffect](
      serviceFactory: Uri => Resource[M, Service[FH.Request, FH.Response]],
      streaming: Boolean = true): Client[M] =
    Utils.mkServiceFactoryClient(serviceFactory, streaming)
}
