package blackbird

//import cats.implicits._
import cats.effect._
import com.twitter.finagle
import com.twitter.finagle.http
import com.twitter.util.{Await, Duration}
import org.http4s._
import org.http4s.client.Client
import org.http4s.server.Server
import fs2._

import scala.concurrent.ExecutionContext

trait ClientSuite extends munit.CatsEffectSuite {
  val (server, shutdownServer)                                                    = ClientSuite.testServer.allocated.unsafeRunSync()
  val address                                                                     = s"http://localhost:${server.address.getPort}"
  val finagleClient: finagle.Service[finagle.http.Request, finagle.http.Response] = createFinagleClient
  //    finagle.Http.client.withRequestTimeout(Duration.fromSeconds(5)).newService(s"localhost:${server.address.getPort}")
  val client: Client[IO]                                                          = Blackbird.apply[IO](finagleClient, streaming = false)

  def createFinagleClient: finagle.Service[finagle.http.Request, finagle.http.Response]

  def checkExpected(path: String, expected: String): IO[Unit] = {
    client.expect[String](s"$address$path").map { got =>
      assertEquals(got, expected)
    }
  }

  def request(method: Method, path: String): Request[IO] = Request[IO](
    method = method,
    uri = Uri.unsafeFromString(s"$address$path")
  )

  test(s"GET ${TestRoutes.SimplePath}") {
    checkExpected(TestRoutes.SimplePath, "simple path")
  }

  test(s"GET ${TestRoutes.ChunkedPath} ") {
    checkExpected(TestRoutes.ChunkedPath, "chunk")
  }

  test(s"GET ${TestRoutes.DelayedPath}") {
    checkExpected(TestRoutes.DelayedPath, "delayed path")
  }

  test(s"GET ${TestRoutes.NoContentPath}") {
    checkExpected(TestRoutes.NoContentPath, "")
  }

  test(s"GET ${TestRoutes.EmptyNotFoundPath}") {
    client
      .status(request(Method.GET, TestRoutes.EmptyNotFoundPath))
      .map(st => assertEquals(st, Status.NotFound))
  }

  test(s"GET ${TestRoutes.NotFoundPath}") {
    val f = for {
      resp <- Stream.resource(client.run(request(Method.GET, TestRoutes.NotFoundPath)))
      _     = assertEquals(resp.status, Status.NotFound)
      body <- resp.bodyText
      _     = assertEquals(body, "not found")
    } yield ()
    f.compile.drain
  }

  test(s"PUT ${TestRoutes.EchoPath}") {
    client
      .expect[String](
        request(Method.PUT, TestRoutes.EchoPath).withEntity("put request")
      )
      .map(body => assertEquals(body, "put request"))
  }

  test(s"PUT ${TestRoutes.EchoPath} (chunked)") {
    client
      .expect[String](
        request(Method.PUT, TestRoutes.EchoPath)
          .withEntity(Stream.emits("chunk".toSeq.map(_.toString)).covary[IO])
      )
      .map { body =>
        assertEquals(body, "chunk")
      }
  }

  override def afterAll(): Unit = {
    shutdownServer.unsafeRunSync()
    Await.result(finagleClient.close())
  }
}

class NonStreamingClientSuite extends ClientSuite {
  override def createFinagleClient: finagle.Service[http.Request, http.Response] =
    finagle.Http.client
      .withRequestTimeout(Duration.fromSeconds(5))
      .newService(s"localhost:${server.address.getPort}")
}

class StreamingClientSuite extends ClientSuite {
  override def createFinagleClient: finagle.Service[http.Request, http.Response] =
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
