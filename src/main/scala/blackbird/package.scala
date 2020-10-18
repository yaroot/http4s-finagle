import cats.data.Kleisli
import com.twitter.finagle.{Service, http => FH}
import org.http4s.Uri

package object blackbird {
  type Factory[F[_]] = Kleisli[F, ClientKey, HttpService]

  type HttpService = Service[FH.Request, FH.Response]
  type ClientKey   = (Uri.Scheme, Uri.Authority)
}
