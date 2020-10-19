package blackbird

import cats.effect._
import com.twitter.finagle
import com.twitter.util.{Await, Duration}
import org.http4s.client.Client
import org.http4s.server.Server

import scala.concurrent.ExecutionContext

trait ClientSuite extends munit.CatsEffectSuite with RouteSuite {
  val (server, shutdownServer) = ClientSuite.testServer.allocated.unsafeRunSync()
  val address                  = s"http://localhost:${server.address.getPort}"
  val finagleClient            = createFinagleClient
  val client: Client[IO]       = Blackbird.apply[IO](finagleClient, streaming = false)

  override def afterAll(): Unit = {
    shutdownServer.unsafeRunSync()
    Await.result(finagleClient.close())
  }

  def createFinagleClient: finagle.Service[finagle.http.Request, finagle.http.Response]
}

class NonStreamingClientSuite extends ClientSuite {
  override def createFinagleClient: finagle.Service[finagle.http.Request, finagle.http.Response] =
    finagle.Http.client
      .withRequestTimeout(Duration.fromSeconds(5))
      .newService(s"localhost:${server.address.getPort}")
}

class StreamingClientSuite extends ClientSuite {
  override def createFinagleClient: finagle.Service[finagle.http.Request, finagle.http.Response] =
    finagle.Http.client
      .withRequestTimeout(Duration.fromSeconds(5))
      .withStreaming(true)
      .newService(s"localhost:${server.address.getPort}")
}

object ClientSuite {
  import org.http4s.server.blaze.BlazeServerBuilder

  def testServer(implicit timer: Timer[IO], cs: ContextShift[IO]): Resource[IO, Server[IO]] = {
    BlazeServerBuilder[IO](ExecutionContext.global)
      .withHttpApp(TestRoutes.routes[IO])
      .bindAny()
      .resource
  }
}
