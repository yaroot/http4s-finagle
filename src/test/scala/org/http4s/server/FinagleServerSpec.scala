package org.http4s.server

import scala.io.Source
import scala.concurrent.duration._
import java.nio.charset.StandardCharsets

import org.http4s._
import org.http4s.dsl.io._
import org.http4s.server.finagle._
import cats.effect._
import cats.implicits._
import com.twitter.finagle.{Http => FHttp}
import java.net.{HttpURLConnection, InetSocketAddress, URL}
import org.specs2.concurrent.ExecutionEnv
import org.specs2.execute.FailureException

class FinagleServerSpec(implicit ee: ExecutionEnv) extends Http4sSpec {
  val routes: HttpRoutes[IO] = HttpRoutes.of {
    case req @ POST -> Root / "echo" =>
      Ok(req.body)
    case GET -> Root / "never" =>
      IO.never
    case GET -> Root / "slow" =>
      implicitly[Timer[IO]].sleep(50.millis) *> Ok("slow")
  }

  val server = FHttp.server.withStreaming(true).serveRoutes[IO](":0", routes)
  val port = server.boundAddress.asInstanceOf[InetSocketAddress].getPort

  def get(path: String): IO[String] =
    contextShift.evalOn(testBlockingExecutionContext)(
      IO(
        Source
          .fromURL(new URL(s"http://127.0.0.1:${port}$path"))
          .getLines
          .mkString))

  def post(path: String, body: String): IO[String] =
    contextShift.evalOn(testBlockingExecutionContext)(IO {
      val url = new URL(s"http://127.0.0.1:${port}$path")
      val conn = url.openConnection().asInstanceOf[HttpURLConnection]
      val bytes = body.getBytes(StandardCharsets.UTF_8)
      conn.setRequestMethod("POST")
      conn.setRequestProperty("Content-Length", bytes.size.toString)
      conn.setDoOutput(true)
      conn.getOutputStream.write(bytes)
      Source.fromInputStream(conn.getInputStream, StandardCharsets.UTF_8.name).getLines.mkString
    })

  "A server" should {
    "be able to echo its input" in {
      val input = """{ "Hello": "world" }"""
      post("/echo", input) must returnValue(startWith(input))
    }
  }

  "Timeout" should {
    "not fire prematurely" in {
      get("/slow") must returnValue("slow")
    }

    "fire on timeout" in {
      get("/never")
        .unsafeToFuture() must throwAn[FailureException].awaitFor(5.seconds) // IOException
    }
  }

}
