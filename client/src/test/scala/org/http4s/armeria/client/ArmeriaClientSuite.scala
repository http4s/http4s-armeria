/*
 * Copyright 2020 http4s.org
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.http4s
package armeria
package client

import cats.effect.{IO, Resource}
import com.linecorp.armeria.client.ClientRequestContext
import com.linecorp.armeria.client.logging.{ContentPreviewingClient, LoggingClient}
import com.linecorp.armeria.common.{
  ExchangeType,
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
import java.util.concurrent.{BlockingQueue, LinkedBlockingQueue}
import munit.CatsEffectSuite
import org.http4s.syntax.all._
import org.http4s.client.Client
import org.slf4j.{Logger, LoggerFactory}
import scala.concurrent.duration._

class ArmeriaClientSuite extends CatsEffectSuite {
  private val logger: Logger = LoggerFactory.getLogger(getClass)

  private val clientContexts: BlockingQueue[ClientRequestContext] = new LinkedBlockingQueue

  private def setUp(): IO[(Server, Client[IO])] = {
    val server = Server
      .builder()
      .decorator(ContentPreviewingService.newDecorator(Int.MaxValue))
      .decorator(LoggingService.newDecorator())
      .service(
        "/{name}",
        new HttpService {
          override def serve(ctx: ServiceRequestContext, req: HttpRequest): HttpResponse = {
            logger.info(s"req = $req")
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
                .thenApply[HttpResponse](agg => HttpResponse.of(s"Hello, ${agg.contentUtf8()}!")))
        }
      )
      .service(
        "/client-streaming",
        new HttpService {
          override def serve(ctx: ServiceRequestContext, req: HttpRequest): HttpResponse = {
            val body: IO[Option[String]] = req
              .toStreamBuffered[IO](1)
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
              .toStreamBuffered[IO](1)
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
        .withDecorator { (delegate, ctx, req) =>
          clientContexts.offer(ctx)
          delegate.execute(ctx, req)
        }
        .withResponseTimeout(10.seconds)
        .build()

      server -> client
    }
  }

  override def afterEach(context: AfterEach): Unit =
    clientContexts.clear()

  private val fixture =
    ResourceSuiteLocalFixture("fixture", Resource.make(setUp())(x => IO(x._1.stop()).void))

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
    val req = Request(method = Method.POST, uri = uri"/post", entity = Entity.Default(body, None))
    val response = client.expect[String](req).unsafeRunSync()
    assertEquals(response, "Hello, Armeria!")
  }

  test("ExchangeType - disable request-streaming") {
    val (_, client) = fixture()
    val req = Request[IO](method = Method.POST, uri = uri"/post").withEntity("Armeria")
    val response = client.expect[String](req).unsafeRunSync()
    assertEquals(response, "Hello, Armeria!")
    val exchangeType = clientContexts.take().exchangeType()
    assertEquals(exchangeType, ExchangeType.RESPONSE_STREAMING)
  }

  test("client-streaming") {
    val (_, client) = fixture()

    val body = Stream
      .range(1, 6)
      .covary[IO]
      .map(_.toString)
      .through(text.utf8.encode)

    val req = Request(
      method = Method.POST,
      uri = uri"/client-streaming",
      entity = Entity.Default(body, None))
    val response = client.expect[String](req).unsafeRunSync()
    assertEquals(response, "1 2 3 4 5")
  }

  test("bidi-streaming") {
    val (_, client) = fixture()

    val body = Stream
      .range(1, 6)
      .covary[IO]
      .map(_.toString)
      .through(text.utf8.encode)

    val req =
      Request(method = Method.POST, uri = uri"/bidi-streaming", entity = Entity.Default(body, None))
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
