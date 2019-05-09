package org.http4s.server

import java.net.SocketAddress
import cats.effect.ConcurrentEffect
import com.twitter.finagle.{ListeningServer, Http => FHttp, http => fhttp}
import org.http4s._
import org.http4s.finagle.Finagle

package object finagle {
  implicit class finagleServer(val svr: FHttp.Server) extends AnyVal {
    def serveRoutes[F[_]: ConcurrentEffect](addr: String, routes: HttpRoutes[F]): ListeningServer =
      svr.serve(addr, Finagle.mkService[F](routes, streaming()))

    def serveRoutes[F[_]: ConcurrentEffect](addr: SocketAddress, routes: HttpRoutes[F]): ListeningServer =
      svr.serve(addr, Finagle.mkService[F](routes, streaming()))

    private def streaming(): Boolean =
      svr.params[fhttp.param.Streaming].enabled
  }
}
