package valvetrain.http4s.finagle.server


import java.net.SocketAddress

import cats.effect.IO
import com.twitter.finagle.{ListeningServer, Http => FHttp}
import com.twitter.util.Await
import org.http4s._
import org.http4s.dsl.Http4sDsl
import valvetrain.http4s.finagle.common.Finagle


object FinagleBuilder {
  def apply(server: FHttp.Server): FinagleBuilder = new FinagleBuilder {
    def serve(addr: String, service: HttpService[IO]): ListeningServer = {
      server.serve(addr, Finagle.mkService(service))
    }

    def serve(addr: SocketAddress, service: HttpService[IO]): ListeningServer = {
      server.serve(addr, Finagle.mkService(service))
    }
  }
}

trait FinagleBuilder {
  def serve(addr: String, service: HttpService[IO]): ListeningServer
  def serve(addr: SocketAddress, service: HttpService[IO]): ListeningServer
}


object Main {
  def main(args: Array[String]): Unit = {
    val server = FinagleBuilder(FHttp.server)
      .serve(":8080", Http4sService.service)
    println(server.boundAddress)
    Await.result(server)
  }
}

object Http4sService extends Http4sDsl[IO] {
  val service: HttpService[IO] = HttpService[IO] {
    case GET -> Root => Ok("ok")
  }
}


