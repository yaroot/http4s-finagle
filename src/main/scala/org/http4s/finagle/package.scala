package org.http4s.finagle

import cats.effect._
import cats.syntax.functor._
import cats.syntax.flatMap._
import com.twitter.finagle.http.{Message => FMessage, Request => FRequest, Response => FResponse}
import com.twitter.finagle.{http => FH}
import com.twitter.finagle.{CancelledRequestException, Service => Svc}
import com.twitter.io.{Buf, Pipe, Reader}
import com.twitter.util._
import fs2.{Chunk, Stream}
import io.chrisdavenport.vault.{Key, Vault}
import org.http4s._
import org.http4s.implicits._
import org.http4s.client.Client

object Ctx {
  def restore[F[_]](req: Request[F]): Unit =
    req.attributes.lookup(Keys.local).foreach(Local.restore)

  object Keys {
    val local: Key[Local.Context] = Key.newKey[IO, Local.Context].unsafeRunSync()
  }
}

object Utils {
  def isChunking(h: Header): Boolean = h match {
    case encoding: headers.`Transfer-Encoding` => encoding.hasChunked
    case _ => false
  }

  def fromHttp4sRequest[M[_]: ConcurrentEffect](
      req: Request[M],
      streaming: Boolean): M[FRequest] = {
    val version = if (req.httpVersion.minor == 0) FH.Version.Http10 else FH.Version.Http11
    val method = FH.Method(req.method.name)
    val uri = req.uri.copy(scheme = None, authority = None).withoutFragment.renderString

    val host = req.uri.authority.map(_.host.value)

    def setHeaders(r: FRequest, headers: Headers): FRequest = {
      headers.foreach { h =>
        val _ = r.headerMap.add(h.name.value, h.value)
      }
      host.foreach(r.headerMap.add("Host", _))
      r
    }

    if (streaming) {
      val freq = FRequest(version, method, uri, readBody(req.body))
      setHeaders(freq, req.headers)
      ConcurrentEffect[M].point(freq)
    } else {
      val body = accumulateContent(req.body)
      body.map { buf =>
        val freq = FRequest(version, method, uri)
        freq.content = buf
        setHeaders(freq, req.headers.filter(!isChunking(_)))
      }
    }
  }

  def toHttp4sResponse[M[_]: ConcurrentEffect](
      response: FResponse
  ): M[Response[M]] = {
    val resp = for {
      statusCode <- Status.fromInt(response.statusCode)
      headers = response.headerMap.toList.map { case (k, v) => Header(k, v).parsed }

      httpVersion = if (response.version.minor == 1) HttpVersion.`HTTP/1.1`
      else HttpVersion.`HTTP/1.0`
    } yield
      Response[M](
        statusCode,
        httpVersion,
        Headers(headers.toList),
        liftMessageBody(response)
      )

    ConcurrentEffect[M].fromEither(resp)
  }

  def fromFinagleVersion(v: FH.Version): HttpVersion =
    v match {
      case FH.Version.Http10 => HttpVersion.`HTTP/1.0`
      case FH.Version.Http11 => HttpVersion.`HTTP/1.1`
      case _ => HttpVersion.`HTTP/1.1`
    }

  def fromFinagleRequest[M[_]: ConcurrentEffect](req: FRequest): Either[ParseFailure, Request[M]] =
    for {
      method <- Method.fromString(req.method.name)
      uri <- Uri.fromString(req.uri)
      headers = req.headerMap.toList.map { case (k, v) => Header(k, v).parsed }
      version = fromFinagleVersion(req.version)
      twitterLocal = Local.save()
    } yield {
      Request[M](
        method = method,
        uri = uri,
        httpVersion = version,
        headers = Headers(headers.toList),
        body = liftMessageBody[M](req),
        attributes = Vault.empty.insert(Ctx.Keys.local, twitterLocal)
      )
    }

  // TODO restore Finagle Contexts
  def toFinagleResponse[M[_]: ConcurrentEffect](
      response: Response[M],
      streaming: Boolean): Future[FResponse] =
    if (streaming && response.headers.exists(isChunking)) {
      val reader = readBody(response.body)
      val version =
        if (response.httpVersion == HttpVersion.`HTTP/1.0`) FH.Version.Http10 else FH.Version.Http11
      val status = FH.Status.fromCode(response.status.code)
      val fresp = FResponse(version, status, reader)
      response.headers.foreach { h =>
        val _ = fresp.headerMap.set(h.name.value, h.value)
      }
      Future.value(fresp)
    } else {
      val body = accumulateContent[M](response.body)
      eval(body).map { content =>
        val fresp = FResponse()
        fresp.statusCode = response.status.code
        fresp.content = content
        response.headers.foreach { header =>
          if (!isChunking(header)) {
            val _ = fresp.headerMap.set(header.name.value, header.value)
          }
        }
        fresp
      }
    }

  def mkService[M[_]: ConcurrentEffect](
      routes: HttpRoutes[M],
      streaming: Boolean): Svc[FRequest, FResponse] =
    Svc.mk[FRequest, FResponse] { freq =>
      fromFinagleRequest(freq) match {
        case Left(exc) => Future.exception[FResponse](exc)
        case Right(req) =>
          eval(routes.orNotFound.run(req))
            .flatMap(toFinagleResponse(_, streaming))
      }
    }

  def mkClient[M[_]: ConcurrentEffect](
      service: Svc[FRequest, FResponse],
      streaming: Boolean): Client[M] = {
    val execute: Request[M] => M[Response[M]] = { req: Request[M] =>
      fromHttp4sRequest(req, streaming)
        .flatMap { freq =>
          lift(service(freq))
            .flatMap(toHttp4sResponse(_))
        }
    }
    val run = (req: Request[M]) => Resource.make(execute(req))(_ => ConcurrentEffect[M].unit)
    Client(run)
  }

  def mkServiceFactoryClient[M[_]: ConcurrentEffect](
      serviceFactory: Uri => Resource[M, Svc[FRequest, FResponse]],
      streaming: Boolean): Client[M] = {

    val client = (req: Request[M]) => {
      serviceFactory(req.uri).use { svc =>
        fromHttp4sRequest(req, streaming)
          .flatMap(r => lift(svc(r)))
          .flatMap(toHttp4sResponse(_))
      }
    }

    Client(r => Resource.liftF(client(r)))
  }

  /** Convert a Future into IO without evaluating it */
  def lift[M[_], A](f: => Future[A])(implicit F: ConcurrentEffect[M]): M[A] = {
    def thunk(): M[A] = {
      val ff = f
      F.cancelable[A] { cb =>
        ff.respond {
          case Return(a) => cb(Right(a))
          case Throw(e) => cb(Left(e))
        }
        F.delay(ff.raise(new CancelledRequestException()))
      }
    }
    F.suspend(thunk())
  }

  /** evaluate effect M[A] and return a Future */
  def eval[M[_]: ConcurrentEffect, A](ma: M[A])(implicit F: ConcurrentEffect[M]): Future[A] = {
    val promise = Promise[A]()
    (F.runCancelable(ma) _)
      .andThen(_.map { cancel =>
        promise.setInterruptHandler {
          case _ => F.toIO(cancel).unsafeRunAsyncAndForget()
        }
      })(e => IO(e.fold(promise.setException, promise.setValue)))
      .unsafeRunSync()
    promise
  }

  /** lift Buf into EntityBody[M] */
  def liftBuf[M[_]: ConcurrentEffect](buf: Buf): EntityBody[M] =
    if (buf.isEmpty) Stream.empty.covary[M]
    else {
      val bs = Buf.ByteArray.Shared.extract(buf)
      Stream.chunk(Chunk.bytes(bs)).covary[M]
    }

  def liftBodyStream[M[_]: ConcurrentEffect](r: Reader[Buf]): EntityBody[M] = {
    val result = Reader
      .toAsyncStream(r)
      .foldLeft(Buf.Empty)(_.concat(_))
    val body = lift[M, Buf](result)
    Stream
      .eval(body)
      .flatMap(liftBuf(_))
  }

  def liftMessageBody[M[_]: ConcurrentEffect](r: FMessage): EntityBody[M] =
    if (r.isChunked) {
      liftBodyStream(r.reader)
    } else {
      liftBuf(r.content)
    }

  /** accumulate content body */
  def accumulateContent[M[_]: ConcurrentEffect](body: EntityBody[M]): M[Buf] =
    body.chunks.compile.fold(Buf.Empty) { (accu, chunk) =>
      accu.concat(Buf.ByteArray.Owned(chunk.toArray))
    }

  def readBody[M[_]](body: EntityBody[M])(implicit F: ConcurrentEffect[M]): Reader[Buf] = {
    val pipe = new Pipe[Buf]()
    def writeToPipe(chunk: Chunk[Byte]): Future[Unit] = {
      val buf = Buf.ByteArray.Owned(chunk.toArray)
      pipe.write(buf)
    }
    val content = body.chunks.evalMap(chunk => lift(writeToPipe(chunk)))
    val _ = eval(content.compile.drain).ensure { val _ = pipe.close() }
    pipe
  }
}

