package org.http4s.client

import cats.data.Kleisli
import com.twitter.finagle.{http => FH, Service}
import org.http4s.Uri

package object finagle {
  type Factory[F[_]] = Kleisli[F, ClientKey, HttpService]

  type HttpService = Service[FH.Request, FH.Response]
  type ClientKey   = (Uri.Scheme, Uri.Authority)
}
