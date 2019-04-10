package org.http4s.server

import java.net.SocketAddress
import cats.effect.ConcurrentEffect
import com.twitter.finagle.{ListeningServer, Http => FHttp, http => fhttp}
import org.http4s._
import org.http4s.finagle.Utils

package object finagle {
  implicit class finagleServer(val svr: FHttp.Server) extends AnyVal {
    def serveRoutes[M[_]: ConcurrentEffect](addr: String, routes: HttpRoutes[M]): ListeningServer =
      svr.serve(addr, Utils.mkService[M](routes, streaming()))

    def serveRoutes[M[_]: ConcurrentEffect](
        addr: SocketAddress,
        routes: HttpRoutes[M]): ListeningServer =
      svr.serve(addr, Utils.mkService[M](routes, streaming()))

    private def streaming(): Boolean =
      svr.params[fhttp.param.Streaming].enabled
  }
}
