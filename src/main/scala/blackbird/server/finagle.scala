package blackbird

import java.net.SocketAddress
import cats.effect.ConcurrentEffect
import com.twitter.finagle.{ListeningServer, Http => FHttp, http => fhttp}
import org.http4s._

package object finagle {
  implicit class finagleServer(val svr: FHttp.Server) extends AnyVal {
    def serveRoutes[F[_]: ConcurrentEffect](addr: String, app: HttpApp[F]): ListeningServer =
      svr.serve(addr, impl.BlackbirdImpl.mkService[F](app, streaming()))

    def serveRoutes[F[_]: ConcurrentEffect](addr: SocketAddress, app: HttpApp[F]): ListeningServer =
      svr.serve(addr, impl.BlackbirdImpl.mkService[F](app, streaming()))

    private def streaming(): Boolean =
      svr.params[fhttp.param.Streaming].enabled
  }
}
