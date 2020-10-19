package blackbird

import java.net.InetSocketAddress

import cats.effect._
import com.twitter.finagle
import com.twitter.finagle.Http
import com.twitter.util.{Await, Duration}
import org.http4s.client.blaze.BlazeClientBuilder

import scala.concurrent.ExecutionContext

trait ServerSuite extends munit.CatsEffectSuite with RouteSuite {
  val server = Blackbird.serveRoutes[IO](
    "localhost:0",
    TestRoutes.routes[IO],
    makeServer
  )
  def makeServer: finagle.Http.Server

  val port: Int                = server.boundAddress.asInstanceOf[InetSocketAddress].getPort
  val address: String          = s"http://localhost:${port}"
  val (client, shutdownClient) = BlazeClientBuilder[IO](ExecutionContext.global).resource.allocated.unsafeRunSync()

  override def afterAll(): Unit = {
    Await.result(server.close())
    shutdownClient.unsafeRunSync()
  }
}

class StreamingServerSuite extends ServerSuite {
  def makeServer: Http.Server = finagle.Http.server.withRequestTimeout(Duration.fromSeconds(5)).withStreaming(true)
}

class NonStreamingServerSuite extends ServerSuite {
  def makeServer: Http.Server = finagle.Http.server.withRequestTimeout(Duration.fromSeconds(5))
}
