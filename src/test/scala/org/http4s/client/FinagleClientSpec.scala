package org.http4s.client

import org.http4s.{Query, Uri}
import cats.syntax.apply._
import cats.effect._
import com.twitter.finagle.Service
import com.twitter.finagle.http.{Request, Response}
import FinagleClientSpec._

class FinagleClientSpec extends ClientRouteTestBattery("FinagleClient") {
  def clientResource = Resource.make(createClient())(_ => IO.unit)
}

object FinagleClientSpec {
  import com.twitter.finagle.Http
  val clientMaker: Uri => Resource[IO, Service[Request, Response]] = { uri =>
    val client = IO {
      val dest =
        uri.copy(scheme = None, path = "", query = Query.empty, fragment = None).renderString

      val hostname = uri.host.map(_.value)
      val client = Http.client.withStreaming(enabled = true)
      val isHttps = uri.scheme.contains(Uri.Scheme.https)
      val maybeTlsClient = if (isHttps) {
        hostname.fold(client.withTlsWithoutValidation)(client.withTls)
      } else {
        client
      }
      maybeTlsClient.newService(dest)
    }
    Resource.make(client)(c => IO(c.close()) *> IO.unit)
  }

  implicit val contextShift: ContextShift[IO] =
    IO.contextShift(scala.concurrent.ExecutionContext.global)

  def createClient() = IO(finagle.FinagleClient.fromServiceFactory[IO](clientMaker))
}
