package valvetrain.http4s.finagle.client

import cats.effect._
import com.twitter.finagle.{ http => finhttp }
import com.twitter.finagle.{Service => Svc}
import org.http4s.client.Client
import valvetrain.http4s.finagle.common.Finagle


object Client {
  def make(client: Svc[finhttp.Request, finhttp.Response]): Client[IO] = {
    Finagle.mkClient(client)
  }
}
