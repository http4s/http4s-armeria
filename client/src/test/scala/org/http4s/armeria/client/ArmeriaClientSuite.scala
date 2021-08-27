package org.http4s
package armeria
package client

import cats.effect.{IO, Resource}
import com.linecorp.armeria.client.logging.{ContentPreviewingClient, LoggingClient}
import com.linecorp.armeria.common.{
  HttpData,
  HttpRequest,
  HttpResponse,
  HttpStatus,
  ResponseHeaders
}
import com.linecorp.armeria.server.logging.{ContentPreviewingService, LoggingService}
import com.linecorp.armeria.server.{HttpService, Server, ServiceRequestContext}
import fs2._
import fs2.interop.reactivestreams._
import munit.CatsEffectSuite
import org.http4s.client.Client
import org.http4s.implicits.http4sLiteralsSyntax
import org.slf4j.LoggerFactory

import scala.concurrent.duration._

class ArmeriaClientSuite extends CatsEffectSuite {
  private val logger = LoggerFactory.getLogger(getClass)

  private def setUp(): IO[(Server, Client[IO])] = {
    val server = Server
      .builder()
      .decorator(ContentPreviewingService.newDecorator(Int.MaxValue))
      .decorator(LoggingService.newDecorator())
      .service(
        "/{name}",
        new HttpService {
          override def serve(ctx: ServiceRequestContext, req: HttpRequest): HttpResponse = {
            logger.info(s"req = ${req}")
            HttpResponse.of(s"Hello, ${ctx.pathParam("name")}!")
          }
        }
      )
      .service(
        "/post",
        new HttpService {
          override def serve(ctx: ServiceRequestContext, req: HttpRequest): HttpResponse =
            HttpResponse.from(
              req
                .aggregate()
                .thenApply(agg => HttpResponse.of(s"Hello, ${agg.contentUtf8()}!")))
        }
      )
      .service(
        "/client-streaming",
        new HttpService {
          override def serve(ctx: ServiceRequestContext, req: HttpRequest): HttpResponse = {
            val body: IO[Option[String]] = req
              .toStream[IO]
              .collect { case data: HttpData => data.toStringUtf8 }
              .reduce(_ + " " + _)
              .compile
              .last

            val writer = HttpResponse.streaming()
            body.unsafeRunAsync {
              case Left(ex) =>
                writer.close(ex)
              case Right(value) =>
                writer.write(ResponseHeaders.of(HttpStatus.OK))
                writer.write(HttpData.ofUtf8(value.getOrElse("none")))
                writer.close()
            }
            writer
          }
        }
      )
      .service(
        "/bidi-streaming",
        new HttpService {
          override def serve(ctx: ServiceRequestContext, req: HttpRequest): HttpResponse = {
            val writer = HttpResponse.streaming()
            writer.write(ResponseHeaders.of(HttpStatus.OK))
            req
              .toStream[IO]
              .collect { case data: HttpData =>
                writer.write(HttpData.ofUtf8(s"${data.toStringUtf8}!"))
              }
              .onFinalize(IO(writer.close()))
              .compile
              .drain
              .unsafeRunAsync {
                case Right(_) => writer.close()
                case Left(ex) => writer.close(ex)
              }
            writer
          }
        }
      )
      .build()

    IO(server.start().join()) *> IO {
      val client = ArmeriaClientBuilder
        .unsafe[IO](s"http://127.0.0.1:${server.activeLocalPort()}")
        .withDecorator(ContentPreviewingClient.newDecorator(Int.MaxValue))
        .withDecorator(LoggingClient.newDecorator())
        .withResponseTimeout(10.seconds)
        .build()

      server -> client
    }
  }

  private val fixture =
    ResourceSuiteLocalFixture("fixture", Resource.make(setUp())(x => IO(x._1.stop().join()).void))

  override def munitFixtures = List(fixture)

  test("get") {
    val (_, client) = fixture()
    val response = client.expect[String]("Armeria").unsafeRunSync()
    assertEquals(response, "Hello, Armeria!")
  }

  test("absolute-uri") {
    val (server, _) = fixture()
    val clientWithoutBaseUri = ArmeriaClientBuilder[IO]().resource.allocated.unsafeRunSync()._1
    val uri = s"http://127.0.0.1:${server.activeLocalPort()}/Armeria"
    val response = clientWithoutBaseUri.expect[String](uri).unsafeRunSync()
    assertEquals(response, "Hello, Armeria!")
  }

  test("post") {
    val (_, client) = fixture()
    val body = Stream.emits("Armeria".getBytes).covary[IO]
    val req = Request(method = Method.POST, uri = uri"/post", body = body)
    val response = client.expect[String](IO(req)).unsafeRunSync()
    assertEquals(response, "Hello, Armeria!")
  }

  test("client-streaming") {
    val (_, client) = fixture()

    val body = Stream
      .range(1, 6)
      .covary[IO]
      .map(_.toString)
      .through(text.utf8.encode)

    val req = Request(method = Method.POST, uri = uri"/client-streaming", body = body)
    val response = client.expect[String](IO(req)).unsafeRunSync()
    assertEquals(response, "1 2 3 4 5")
  }

  test("bidi-streaming") {
    val (_, client) = fixture()

    val body = Stream
      .range(1, 6)
      .covary[IO]
      .map(_.toString)
      .through(text.utf8.encode)

    val req = Request(method = Method.POST, uri = uri"/bidi-streaming", body = body)
    val response = client
      .stream(req)
      .flatMap(res => res.bodyText)
      .compile
      .toList
      .unsafeRunSync()
      .reduce(_ + " " + _)
    assertEquals(response, "1! 2! 3! 4! 5!")
  }
}
