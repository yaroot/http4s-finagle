package valvetrain.http4s.finagle.common

import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicReference

import cats.effect.IO
import cats.data.Kleisli
import com.twitter.finagle.context.RemoteInfo
import com.twitter.finagle.http.{Request => FRequest, Response => FResponse}
import com.twitter.finagle.http
import com.twitter.finagle.transport.Transport
import com.twitter.finagle.{CancelledRequestException, Service => Svc}
import com.twitter.io.Buf
import com.twitter.util._
import fs2.{Chunk, Stream}
import org.http4s.Request.Connection
import org.http4s._
import org.http4s.client.{Client, DisposableResponse}


// TODO abstract IO[_], cleanup all interleave IO/Future, ensure all Futures are thunked
object Finagle {
  object Ctx {
    def restore[F[_]](req: Request[F]): Unit = {
      req.attributes.get(Ctxs.Keys.LocalInfo).foreach(Local.restore)
    }
  }

  def fromHttp4sRequest(req: Request[IO]): IO[FRequest] = {
    val freq = FRequest()
    freq.version = if(req.httpVersion.minor == 0) http.Version.Http10 else http.Version.Http11
    req.headers.foreach { h =>
      freq.headerMap.add(h.name.value, h.value)
    }
    freq.method = http.Method(req.method.name)
    freq.uri = req.uri.copy(scheme = None, authority = None).withoutFragment.renderString

    Utils.accumulateContent(req.body)
      .map { body =>
        freq.content = body
        freq
      }
  }

  def toHttp4sResponse(response: FResponse): Either[ParseFailure, Response[IO]] = {
    for {
      statusCode <- Status.fromInt(response.statusCode)
      content = response.content
      headers  = response.headerMap.toList.map { case (k, v) => Header(k, v).parsed }
      httpVersion  = if (response.version.minor == 1) HttpVersion.`HTTP/1.1` else HttpVersion.`HTTP/1.0`
    } yield Response[IO](
      statusCode,
      httpVersion,
      Headers(headers),
      Utils.extractContent(content)
    )
  }

  def fromFinagleRequest(req: FRequest): Either[ParseFailure, Request[IO]] = {
    for {
      method <- Method.fromString(req.method.name)
      uri <- Uri.fromString(req.uri)
      headers = req.headerMap.toList.map { case (k, v) => Header(k, v).parsed }
      version = if(req.version.minor == 1) HttpVersion.`HTTP/1.1` else HttpVersion.`HTTP/1.0`
      finagleCtx = AttributeEntry(Ctxs.Keys.LocalInfo, Local.save())
    } yield {
      Request[IO](
        method = method,
        uri = uri,
        httpVersion = version,
        headers = Headers(headers),
        body = Utils.extractContent(req.content),
        attributes = AttributeMap(finagleCtx)
      )
    }
  }

  def toFinagleResponse(response: Response[IO]): Future[FResponse] = {
    // TODO restore Finagle Contexts
    val fresp = FResponse()
    fresp.statusCode = response.status.code
    response.headers.foreach { header =>
      fresp.headerMap.add(header.name.value, header.value)
    }

    Utils.fromIO(Utils.accumulateContent(response.body))
      .map { content =>
        fresp.content = content
        fresp
      }
  }

  def mkService(http4s: HttpService[IO]): Svc[FRequest, FResponse] = {
    Svc.mk[FRequest, FResponse] { freq =>
      fromFinagleRequest(freq) match {
        case Left(exc) => Future.exception[FResponse](exc)
        case Right(req) =>
          val svc = http4s.mapF(_.getOrElse(Response.notFound[IO])).run
          Utils.fromIO(svc(req))
            .flatMap(toFinagleResponse)
      }
    }
  }

  def mkClient(service: Svc[FRequest, FResponse]): Client[IO] = {
    val run: Request[IO] => IO[Response[IO]] = { req =>
      fromHttp4sRequest(req)
        .map { freq =>
          service(freq)
            .map(toHttp4sResponse)
            .flatMap(_.fold(Future.exception, Future.value))
        }
        .flatMap(Utils.fromFuture(_))
    }
    val open = Kleisli(run).map(DisposableResponse(_, IO.unit))
    val close = Utils.fromFuture(service.close())
    Client[IO]( open, close )
  }
}

object Utils {
  /** Convert a Future into IO */
  def fromFuture[A](f: => Future[A]): IO[A] = {
    val ff = f
    IO.cancelable { cb =>
      ff.respond {
        case Return(a) => cb(Right(a))
        case Throw(e) => cb(Left(e))
      }
      IO {
        ff.raise(new CancelledRequestException())
      }
    }
  }

  /** Convert an IO into Future */
  def fromIO[A](io: IO[A]): Future[A] = {
    val p = Promise[A]()
    val cancellable = io.unsafeRunCancelable { result =>
      val t = result.fold[Try[A]](Throw(_), Return(_))
      p.updateIfEmpty(t)
    }
    p.setInterruptHandler {
      case _ => cancellable()
    }
    p
  }

  /** Extract Request.content into EntityBody[IO] */
  def extractContent(buf: Buf): EntityBody[IO] = {
    if (buf.isEmpty) Stream.empty.covary[IO]
    else {
      val bs = Buf.ByteArray.Shared.extract(buf)
      Stream.chunk(Chunk.bytes(bs)).covary[IO]
    }
  }

  /** accumulate content body */
  def accumulateContent(body: EntityBody[IO]): IO[Buf] = {
    body.chunks.compile.fold(Buf.Empty) {
      (buf, chunk) => buf concat Buf.ByteArray.Owned(chunk.toArray)
    }
  }
}

object Ctxs {
  trait LocalAddress {
    def get(): Option[InetSocketAddress]
  }

  trait LocalAddressSetter {
    def localAddress: LocalAddress
    def set(inet: InetSocketAddress): Unit
  }

  object LocalAddress {
    def setter(): LocalAddressSetter = new LocalAddressSetter with LocalAddress {
      val ref = new AtomicReference[InetSocketAddress]()
      override def localAddress: LocalAddress = this
      override def set(addr: InetSocketAddress): Unit = ref.set(addr)
      override def get(): Option[InetSocketAddress] = Option(ref.get())
    }
  }

  def connection(localAddress: LocalAddress): Option[Connection] = {
    for {
      remote <- RemoteInfo.Upstream.addr
      local <- localAddress.get()
      secure = Transport.peerCertificate.isDefined
    } yield Connection(
      local.asInstanceOf[InetSocketAddress],
      remote.asInstanceOf[InetSocketAddress],
      secure
    )
  }

  object Keys {
    val LocalInfo: AttributeKey[Local.Context] = AttributeKey[Local.Context]
  }
}
