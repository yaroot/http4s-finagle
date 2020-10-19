package blackbird

import java.util.concurrent.Executors

import cats.data.Kleisli
import cats.effect._
import cats.implicits._
import org.http4s._
import fs2.Stream
import munit.FunSuite
import org.http4s.client.Client

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._

object TestRoutes {
  val SimplePath              = "/simple"
  val ChunkedPath             = "/chunked"
  val DelayedPath             = "/delayed"
  val NoContentPath           = "/no-content"
  val NotFoundPath            = "/not-found"
  val EmptyNotFoundPath       = "/empty-not-found"
  val InternalServerErrorPath = "/internal-server-error"
  val EchoPath                = "/echo"

  def routes[F[_]: Sync: Timer]: HttpApp[F] = Kleisli { req =>
    println(("-->", req.method, req.uri.path))
    val rep = req.uri.path match {
      case EchoPath                =>
        req.decode[String](body => Response[F]().withEntity(body).pure[F])
      case SimplePath              =>
        Response[F]().withEntity("simple path").pure[F]
      case ChunkedPath             =>
        Response[F]().withEntity(Stream.emits("chunk".toSeq.map(_.toString)).covary[F]).pure[F]
      case DelayedPath             =>
        Timer[F].sleep(1.second) *> Response[F]().withEntity("delayed path").pure[F]
      case NoContentPath           =>
        Response[F](Status.NoContent).pure[F]
      case NotFoundPath            =>
        Response[F](Status.NotFound).withEntity("not found").pure[F]
      case EmptyNotFoundPath       =>
        Response[F](Status.NotFound).pure[F]
      case InternalServerErrorPath =>
        Response[F](Status.InternalServerError).pure[F]
      case _                       =>
        Response[F](Status.NotFound).pure[F]
    }
    println(("<-- rep", rep))
    rep
  }

  val executionContext: ExecutionContext = ExecutionContext.fromExecutorService(Executors.newFixedThreadPool(4))
}

trait RouteSuite { self: FunSuite =>
  def address: String
  def client: Client[IO]

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

}
