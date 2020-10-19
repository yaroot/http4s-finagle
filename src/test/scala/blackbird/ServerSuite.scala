package blackbird

import java.net.InetSocketAddress

import cats.effect._
import com.twitter.finagle
import com.twitter.util.{Await, Duration}
import org.http4s.client.blaze.BlazeClientBuilder

import scala.concurrent.ExecutionContext

trait ServerSuite extends munit.CatsEffectSuite with RouteSuite {
  override def munitExecutionContext: ExecutionContext = TestRoutes.executionContext

  val server = Blackbird.serveRoutes[IO](
    "localhost:0",
    TestRoutes.routes[IO],
    finagle.Http.server
      .withRequestTimeout(Duration.fromSeconds(3))
      .withStreaming(streamingToggle)
  )

  def streamingToggle: Boolean

  val port: Int                = server.boundAddress.asInstanceOf[InetSocketAddress].getPort
  val address: String          = s"http://localhost:${port}"
  val (client, shutdownClient) = BlazeClientBuilder[IO](TestRoutes.executionContext).resource.allocated.unsafeRunSync()

  override def afterAll(): Unit = {
    Await.result(server.close())
    shutdownClient.unsafeRunSync()
  }
}

class StreamingServerSuite extends ServerSuite {
  override def streamingToggle: Boolean = true
}

class NonStreamingServerSuite extends ServerSuite {
  override def streamingToggle: Boolean = false
}
