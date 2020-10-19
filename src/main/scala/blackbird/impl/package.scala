package blackbird.impl

import cats.effect._
import cats.implicits._
import com.twitter.finagle.http.{Message => FMessage, Request => FRequest, Response => FResponse}
import com.twitter.finagle.{Service => Svc, http => FH}
import com.twitter.io.{Buf, Pipe, Reader}
import com.twitter.util._
import fs2.{Chunk, Stream}
import io.chrisdavenport.vault.{Key, Vault}
import org.http4s._
import org.http4s.client.Client
import blackbird.ClientFactory

import scala.concurrent.duration.FiniteDuration

object Ctx {
  def restore[F[_]](req: Request[F]): Unit =
    req.attributes.lookup(Keys.local).foreach(Local.restore)

  object Keys {
    val local: Key[Local.Context] = Key.newKey[IO, Local.Context].unsafeRunSync()
  }
}

object Impl {
  def isChunking(h: Header): Boolean =
    h match {
      case encoding: headers.`Transfer-Encoding` => encoding.hasChunked
      case _                                     => false
    }

  def fromHttp4sRequest[F[_]: ConcurrentEffect](req: Request[F], streaming: Boolean): F[FRequest] = {
    val version = ToFinagle.version(req.httpVersion)
    val method  = FH.Method(req.method.name)
    val uri     = req.uri.copy(scheme = None, authority = None).withoutFragment.renderString

    val host = req.uri.authority.map(_.host.value)

    def setHeaders(r: FRequest, headers: Headers): FRequest = {
      headers.foreach { h =>
        val _ = r.headerMap.add(h.name.value, h.value)
      }
      host.foreach(r.headerMap.add("Host", _))
      r
    }

    if (streaming) {
      val freq = FRequest(version, method, uri, unsafeReadBodyStream(req.body))
      setHeaders(freq, req.headers)
      freq.pure[F]
    } else {
      val body = unsafeReadBody(req.body)
      body.map { buf =>
        val freq = FRequest(version, method, uri)
        freq.content = buf
        setHeaders(freq, req.headers.filter(!isChunking(_)))
      }
    }
  }

  def toHttp4sResponse[F[_]: ConcurrentEffect](
    response: FResponse
  ): F[Response[F]] = {
    val resp = for {
      statusCode <- Status.fromInt(response.statusCode)
      headers     = response.headerMap.toList.map { case (k, v) => Header(k, v).parsed }
      httpVersion = FromFinagle.version(response.version)
    } yield Response[F](
      statusCode,
      httpVersion,
      Headers(headers),
      liftMessageBody(response)
    )

    ConcurrentEffect[F].fromEither(resp)
  }

  def fromFinagleRequest[F[_]: ConcurrentEffect](req: FRequest): Either[ParseFailure, Request[F]] =
    for {
      method      <- Method.fromString(req.method.name)
      uri         <- Uri.fromString(req.uri)
      headers      = req.headerMap.toList.map { case (k, v) => Header(k, v).parsed }
      version      = FromFinagle.version(req.version)
      twitterLocal = Local.save()
    } yield {
      Request[F](
        method = method,
        uri = uri,
        httpVersion = version,
        headers = Headers(headers),
        body = liftMessageBody[F](req),
        attributes = Vault.empty.insert(Ctx.Keys.local, twitterLocal)
      )
    }

  // TODO restore Finagle Contexts
  def toFinagleResponse[F[_]: ConcurrentEffect](response: Response[F], streaming: Boolean): Future[FResponse] =
    if (streaming && response.headers.exists(isChunking)) {
      val reader  = unsafeReadBodyStream(response.body)
      val version = ToFinagle.version(response.httpVersion)
      val status  = FH.Status.fromCode(response.status.code)
      val fresp   = FResponse(version, status, reader)
      response.headers.foreach { h =>
        val _ = fresp.headerMap.set(h.name.value, h.value)
      }
      Future.value(fresp)
    } else {
      Converters
        .unsafeRunAsync(unsafeReadBody[F](response.body))
        .map { content =>
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

  def mkService[F[_]: ConcurrentEffect](app: HttpApp[F], streaming: Boolean): Svc[FRequest, FResponse] =
    Svc.mk[FRequest, FResponse] { freq =>
      fromFinagleRequest(freq) match {
        case Left(exc)  => Future.exception[FResponse](exc)
        case Right(req) =>
          Converters
            .unsafeRunAsync(app.run(req))
            .flatMap(toFinagleResponse(_, streaming))
      }
    }

  def mkClient[F[_]](service: Svc[FRequest, FResponse], streaming: Boolean)(implicit
    F: ConcurrentEffect[F]
  ): Client[F] = {
    val execute: Request[F] => F[Response[F]] = { req: Request[F] =>
      fromHttp4sRequest(req, streaming)
        .flatMap { freq =>
          Converters.fromFuture(F.delay(service(freq)))
        }
        .flatMap(toHttp4sResponse(_))
    }
    Client { req: Request[F] =>
      Resource.make(execute(req))(_ => F.unit)
    }
  }

  def mkServiceFactoryClient[F[_]](
    serviceFactory: ClientFactory[F],
    streaming: Boolean
  )(implicit F: ConcurrentEffect[F]): Client[F] = {

    val client = (req: Request[F]) => {
      val key = (req.uri.scheme, req.uri.authority).tupled
      key match {
        case Some(k) =>
          serviceFactory
            .run(k)
            .flatMap { svc =>
              fromHttp4sRequest(req, streaming)
                .flatMap { r =>
                  Converters.fromFuture(F.delay(svc(r)))
                }
            }
            .flatMap(toHttp4sResponse(_))
        case None    =>
          Sync[F].raiseError[Response[F]](new IllegalArgumentException(s"Illegal URL ${req.uri.toString()}"))
      }
    }

    Client(r => Resource.liftF(client(r)))
  }

  def liftMessageBody[F[_]: ConcurrentEffect](r: FMessage): EntityBody[F] =
    if (r.isChunked) {
      Stream
        .eval(readAll[F](r.reader))
        .flatMap { bufs =>
          FromFinagle.toStream[F](bufs.map(FromFinagle.toChunk))
        }
    } else {
      Stream.chunk(FromFinagle.toChunk(r.content)).covary[F]
    }

  /** read body as a Buf */
  def unsafeReadBody[F[_]: ConcurrentEffect](body: EntityBody[F]): F[Buf] =
    body.chunks.compile.fold(Buf.Empty) { (accu, chunk) =>
      accu.concat(ToFinagle.toBuf(chunk))
    }

  /** read body as a stream */
  def unsafeReadBodyStream[F[_]](body: EntityBody[F])(implicit F: ConcurrentEffect[F]): Reader[Buf] = {
    val pipe = new Pipe[Buf]()

    val accu =
      body.chunks
        .evalMap { chunk =>
          Converters.fromFuture(F.delay(pipe.write(ToFinagle.toBuf(chunk))))
        }
        .compile
        .drain

    val close = Converters.fromFuture(F.delay(pipe.close()))

    Converters.unsafeRunAsync(F.guarantee(accu)(close))
    pipe
  }

  def readAll[F[_]](reader: Reader[Buf])(implicit F: ConcurrentEffect[F]): F[Vector[Buf]] = {
    val accu   = Vector.newBuilder[Buf]
    val result = F.delay {
      Reader
        .toAsyncStream(reader)
        .foldLeft(accu) { (accu, b) =>
          accu += b
        }
        .map(_.result())
    }

    Converters.fromFuture(result)
  }
}

object Converters {
  def fromFuture[F[_], A](f: F[Future[A]])(implicit F: ConcurrentEffect[F]): F[A] = {
    f.flatMap { future =>
      future.poll match {
        case Some(Return(a)) => F.pure(a)
        case Some(Throw(e))  => F.raiseError(e)
        case None            =>
          F.cancelable { cb =>
            val _ = future.respond {
              case Return(a) => cb(a.asRight)
              case Throw(e)  => cb(e.asLeft)
            }

            F.uncancelable(F.delay(future.raise(new FutureCancelledException)))
          }
      }
    }
  }

  def unsafeRunAsync[F[_], A](f: F[A])(implicit F: ConcurrentEffect[F]): Future[A] = {
    val p = Promise[A]()

    (F.runCancelable(f) _)
      .andThen(_.map { cancel =>
        p.setInterruptHandler { case ex =>
          p.updateIfEmpty(Throw(ex))
          F.toIO(cancel).unsafeRunAsyncAndForget()
        }
      })(e => IO.delay { val _ = p.updateIfEmpty(e.fold(Throw(_), Return(_))) })

    p
  }
}

object FromFinagle {
  def toChunk(buf: Buf): Chunk[Byte] = {
    val bs = Buf.ByteArray.Shared.extract(buf)
    Chunk.bytes(bs)
  }

  def toStream[F[_]](chunks: Vector[Chunk[Byte]]): Stream[F, Byte] = {
    Stream
      .emits(chunks)
      .flatMap(Stream.chunk)
      .covary[F]
  }

  def version(ver: FH.Version): HttpVersion =
    ver match {
      case FH.Version.Http11 => HttpVersion.`HTTP/1.1`
      case FH.Version.Http10 => HttpVersion.`HTTP/1.0`
      case x                 => HttpVersion(x.major, x.minor)
    }

}

object ToFinagle {
  def duration(d: FiniteDuration): Duration = Duration(d.length, d.unit)

  def toBuf(chunk: Chunk[Byte]): Buf = {
    Buf.ByteArray.Owned(chunk.toArray)
  }

  def version(ver: HttpVersion): FH.Version =
    ver match {
      case HttpVersion.`HTTP/1.0` => FH.Version.Http10
      case HttpVersion.`HTTP/1.1` => FH.Version.Http11
      case HttpVersion.`HTTP/2.0` => FH.Version.Http11
      case x                      => FH.Version(x.major, x.minor)
    }

}
