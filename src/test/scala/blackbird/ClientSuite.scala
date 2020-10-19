package blackbird

import cats.effect._
import com.twitter.finagle
import com.twitter.util.{Await, Duration}
import org.http4s.client.Client
import org.http4s.server.blaze.BlazeServerBuilder

import scala.concurrent.ExecutionContext

trait ClientSuite extends munit.CatsEffectSuite with RouteSuite {
  override def munitExecutionContext: ExecutionContext = ExecutionContext.global

  val (server, shutdownServer) =
    BlazeServerBuilder[IO](ExecutionContext.global)
      .withHttpApp(TestRoutes.routes[IO])
      .bindAny()
      .resource
      .allocated
      .unsafeRunSync()

  val address            = s"http://localhost:${server.address.getPort}"
  val finagleClient      = finagle.Http.client
    .withRequestTimeout(Duration.fromSeconds(3))
    .withStreaming(true)
    .newService(s"localhost:${server.address.getPort}")
  val client: Client[IO] = Blackbird.apply[IO](finagleClient, streaming = false)

  def streamingToggle: Boolean

  override def afterAll(): Unit = {
    shutdownServer.unsafeRunSync()
    Await.result(finagleClient.close())
  }

}

class NonStreamingClientSuite extends ClientSuite {
  override def streamingToggle: Boolean = false
}

class StreamingClientSuite extends ClientSuite {
  override def streamingToggle: Boolean = true
}
