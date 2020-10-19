package blackbird

import java.net.InetSocketAddress

import cats.effect._
import com.twitter.finagle
import com.twitter.util.{Await, Duration}
import org.http4s.client.blaze.BlazeClientBuilder

import scala.concurrent.ExecutionContext

class ServerSuite extends munit.CatsEffectSuite with RouteSuite {
  val server = Blackbird.serveRoutes[IO](
    "localhost:0",
    TestRoutes.routes[IO],
    finagle.Http.server.withRequestTimeout(Duration.fromSeconds(5))
  )

  val port: Int       = server.boundAddress.asInstanceOf[InetSocketAddress].getPort
  val address: String = s"http://localhost:${port}"

  val (client, shutdownClient) = BlazeClientBuilder[IO](ExecutionContext.global).resource.allocated.unsafeRunSync()

  override def afterAll(): Unit = {
    Await.result(server.close())
    shutdownClient.unsafeRunSync()
  }
}
