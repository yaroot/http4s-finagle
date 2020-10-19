//package blackbird
//
//import cats.effect._
//import com.twitter.finagle
//import com.twitter.util.{Await, Duration}
//import org.http4s._
//import org.http4s.client.Client
//import org.http4s.server.Server
//import fs2._
//
//import scala.concurrent.ExecutionContext
//
//class ServerSuite extends munit.CatsEffectSuite {
//  val server = Blackbird.serveRoutes[IO](
//    "localhost:0",
//    TestRoutes.routes[IO],
//    finagle.Http.server.withRequestTimeout(Duration.fromSeconds(5))
//  )
//
//  override def afterAll(): Unit = {
//    Await.result(server.close())
//  }
//}
