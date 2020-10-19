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

  def mkService[F[_]: ConcurrentEffect](app: HttpApp[F], streaming: Boolean): Svc[FRequest, FResponse] =
    Svc.mk[FRequest, FResponse] { freq =>
      println(freq -> streaming)
      FromFinagle.request(freq) match {
        case Left(exc)  => Future.exception[FResponse](exc)
        case Right(req) =>
          ToFinagle
            .asyncEval(app.run(req))
            .flatMap(ToFinagle.response(_, streaming))
      }
    }

  def mkClient[F[_]](service: Svc[FRequest, FResponse], streaming: Boolean)(implicit
    F: ConcurrentEffect[F]
  ): Client[F] = {
    val execute = { req: Request[F] =>
      for {
        freq  <- ToFinagle.request(req, streaming)
        fresp <- FromFinagle.future(F.delay(service(freq)))
        resp  <- FromFinagle.response(fresp)
      } yield resp
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
              ToFinagle
                .request(req, streaming)
                .flatMap { r =>
                  FromFinagle.future(F.delay(svc(r)))
                }
            }
            .flatMap(FromFinagle.response(_))
        case None    =>
          Sync[F].raiseError[Response[F]](new IllegalArgumentException(s"Illegal URL ${req.uri.toString()}"))
      }
    }

    Client(r => Resource.liftF(client(r)))
  }
}

object FromFinagle {
  def isChunking(h: Header): Boolean =
    h match {
      case encoding: headers.`Transfer-Encoding` => encoding.hasChunked
      case _                                     => false
    }

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

  def future[F[_], A](f: F[Future[A]])(implicit F: ConcurrentEffect[F]): F[A] = {
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

    future(result)
  }

  def body[F[_]](r: FMessage)(implicit F: ConcurrentEffect[F]): Stream[F, Byte] = {
    if (r.isChunked) {
      Stream
        .eval(FromFinagle.readAll[F](r.reader))
        .flatMap { bufs =>
          FromFinagle.toStream[F](bufs.map(FromFinagle.toChunk))
        }
    } else {
      Stream.chunk(FromFinagle.toChunk(r.content)).covary[F]
    }
  }

  def response[F[_]: ConcurrentEffect](
    resp: FResponse
  ): F[Response[F]] = {
    val r = for {
      statusCode <- Status.fromInt(resp.statusCode)
      headers     = resp.headerMap.toList.map { case (k, v) => Header(k, v).parsed }
      httpVersion = FromFinagle.version(resp.version)
    } yield Response[F](
      statusCode,
      httpVersion,
      Headers(headers),
      FromFinagle.body(resp)
    )

    ConcurrentEffect[F].fromEither(r)
  }

  def request[F[_]: ConcurrentEffect](req: FRequest): Either[ParseFailure, Request[F]] =
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
        body = FromFinagle.body[F](req),
        attributes = Vault.empty.insert(Ctx.Keys.local, twitterLocal)
      )
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

  def asyncEval[F[_], A](f: F[A])(implicit F: ConcurrentEffect[F]): Future[A] = {
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

  def streamBody[F[_]](body: EntityBody[F])(implicit F: ConcurrentEffect[F]): F[Reader[Buf]] = {
    if (body == EmptyBody) Reader.empty[Buf].pure[F]
    else {
      F.delay {
        val pipe = new Pipe[Buf]()

        val accu =
          body.chunks
            .evalMap { chunk =>
              FromFinagle.future(F.delay(pipe.write(ToFinagle.toBuf(chunk))))
            }
            .compile
            .drain

        val close = FromFinagle.future(F.delay(pipe.close()))

        ToFinagle.asyncEval(F.guarantee(accu)(close))
        pipe
      }
    }
  }

  def accumulateAll[F[_]: ConcurrentEffect](body: Stream[F, Byte]): F[Buf]                       =
    body.chunks.compile.fold(Buf.Empty) { (accu, chunk) =>
      accu.concat(ToFinagle.toBuf(chunk))
    }

  def streamingBody[F[_]](body: EntityBody[F])(implicit F: ConcurrentEffect[F]): Reader[Buf] = {
    val pipe = new Pipe[Buf]()

    val piping =
      body.chunks
        .evalMap { chunk =>
          FromFinagle.future(F.delay(pipe.write(ToFinagle.toBuf(chunk))))
        }
        .compile
        .drain

    val close = FromFinagle.future(F.delay(pipe.close()))

    ToFinagle.asyncEval(F.guarantee(piping)(close))
    pipe
  }

  def request[F[_]: ConcurrentEffect](req: Request[F], streaming: Boolean): F[FRequest] = {
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
      ToFinagle.streamBody(req.body).map { body =>
        val r = FRequest(version, method, uri, body)
        setHeaders(r, req.headers)
        r
      }
    } else {
      ToFinagle.accumulateAll(req.body).map { body =>
        val r = FRequest(version, method, uri)
        r.content = body
        setHeaders(r, req.headers.filter(!FromFinagle.isChunking(_)))
      }
    }
  }
  def response[F[_]: ConcurrentEffect](resp: Response[F], streaming: Boolean): Future[FResponse] =
    if (streaming && resp.headers.exists(FromFinagle.isChunking)) {
      val respf = ToFinagle.streamBody(resp.body).map { body =>
        val version = ToFinagle.version(resp.httpVersion)
        val status  = FH.Status.fromCode(resp.status.code)
        val r       = FResponse(version, status, body)
        resp.headers.foreach { h =>
          val _ = r.headerMap.set(h.name.value, h.value)
        }
        r
      }
      ToFinagle.asyncEval(respf)
    } else {
      ToFinagle
        .asyncEval(ToFinagle.accumulateAll(resp.body))
        .map { content =>
          val r = FResponse()
          r.statusCode = resp.status.code
          r.content = content
          // header op: set content-length, remove chunked transfer
          r.headerMap.set("Content-Length", content.length.toString)
          resp.headers.foreach { header =>
            if (!FromFinagle.isChunking(header)) {
              val _ = r.headerMap.set(header.name.value, header.value)
            }
          }
          r
        }
    }

}
