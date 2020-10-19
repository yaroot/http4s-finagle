package blackbird

import cats.data.Kleisli
import cats.effect._
import cats.implicits._
import org.http4s._
import fs2.Stream

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
    req.uri.path match {
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
  }
}
