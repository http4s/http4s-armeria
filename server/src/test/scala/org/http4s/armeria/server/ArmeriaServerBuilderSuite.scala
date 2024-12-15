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

package org.http4s.armeria.server

import java.net.{HttpURLConnection, URL}
import java.nio.charset.StandardCharsets

import cats.effect.{Deferred, IO}
import cats.implicits._
import com.linecorp.armeria.client.logging.LoggingClient
import com.linecorp.armeria.client.{ClientFactory, WebClient}
import com.linecorp.armeria.common.{HttpData, HttpStatus}
import com.linecorp.armeria.server.logging.LoggingService
import fs2._
import munit.CatsEffectSuite
import org.http4s.dsl.io._
import org.http4s.multipart.Multipart
import org.http4s.{Header, Headers, HttpRoutes}
import org.reactivestreams.{Subscriber, Subscription}
import org.typelevel.ci.CIString

import scala.collection.mutable
import scala.concurrent.duration._
import scala.io.Source
import scala.util.Properties

class ArmeriaServerBuilderSuite extends CatsEffectSuite with ServerFixture {
  override def munitFixtures = List(armeriaServerFixture)

  private val service: HttpRoutes[IO] = HttpRoutes.of {
    case GET -> Root / "thread" / "routing" =>
      val thread = Thread.currentThread.getName
      Ok(thread)

    case GET -> Root / "thread" / "effect" =>
      IO(Thread.currentThread.getName).flatMap(Ok(_))

    case req @ POST -> Root / "echo" =>
      req.decode[IO, String] { r =>
        Ok(r)
      }

    case GET -> Root / "trailers" =>
      Ok("Hello").map(response =>
        response.withTrailerHeaders(IO(Headers(Header.Raw(CIString("my-trailers"), "foo")))))

    case _ -> Root / "never" =>
      IO.never

    case GET -> Root / "stream" =>
      Ok(Stream.range(1, 10).map(_.toString).covary[IO])

    case req @ POST -> Root / "issue2610" =>
      req.decode[IO, Multipart[IO]] { mp =>
        Ok(mp.parts.foldMap(_.body))
      }

    case _ => NotFound()
  }

  protected def configureServer(serverBuilder: ArmeriaServerBuilder[IO]): ArmeriaServerBuilder[IO] =
    serverBuilder
      .withDecorator(LoggingService.newDecorator())
      .bindAny()
      .withRequestTimeout(10.seconds)
      .withGracefulShutdownTimeout(0.seconds, 0.seconds)
      .withMaxRequestLength(1024 * 1024)
      .withHttpRoutes("/service", service)

  lazy val client: WebClient = WebClient
    .builder(s"http://127.0.0.1:${httpPort.get}")
    .decorator(LoggingClient.newDecorator())
    .build()

  // This functionality is desirable, but it's not clear how to achieve it under cats-effect 3
  test("route requests on the service executor".ignore) {
    // A event loop will serve the service to reduce an extra context switching
    assert(
      client
        .get("/service/thread/routing")
        .aggregate()
        .join()
        .contentUtf8()
        .startsWith("armeria-common-worker"))
  }

  // This functionality is desirable, but it's not clear how to achieve it under cats-effect 3
  test("execute the service task on the service executor".ignore) {
    // A event loop will serve the service to reduce an extra context switching
    assert(
      client
        .get("/service/thread/effect")
        .aggregate()
        .join()
        .contentUtf8()
        .startsWith("armeria-common-worker"))
  }

  test("be able to echo its input") {
    val input = """{ "Hello": "world" }"""
    assert(
      client
        .post("/service/echo", input)
        .aggregate()
        .join()
        .contentUtf8()
        .startsWith(input))
  }

  test("be able to send trailers") {
    val response = client.get("/service/trailers").aggregate().join()
    assertEquals(response.status(), HttpStatus.OK)
    assertEquals(response.trailers().get("my-trailers"), "foo")
  }

  test("return a 503 if the server doesn't respond") {
    val noTimeoutClient = WebClient
      .builder(s"http://127.0.0.1:${httpPort.get}")
      .factory(ClientFactory.builder().idleTimeoutMillis(0).build())
      .responseTimeoutMillis(0)
      .decorator(LoggingClient.newDecorator())
      .build()

    assertEquals(
      noTimeoutClient.get("/service/never").aggregate().join().status(),
      HttpStatus.SERVICE_UNAVAILABLE)
  }

  test("reliably handle multipart requests") {
    assume(!Properties.isWin, "Does not work on windows, possibly encoding related?")
    val body =
      """|--aa
         |Content-Disposition: form-data; name="a"
         |Content-Length: 1
         |
         |a
         |--aa--""".stripMargin.replace("\n", "\r\n")

    assertEquals(postChunkedMultipart("/service/issue2610", "aa", body), "a")
  }

  test("reliably handle entity length limiting") {
    val input = List.fill(1024 * 1024 + 1)("F").mkString

    val statusIO = IO(
      postLargeBody("/service/echo", input)
    )

    assertIO(statusIO, HttpStatus.REQUEST_ENTITY_TOO_LARGE.code())
  }

  test("stream") {
    val response = client.get("/service/stream")
    val deferred = Deferred.unsafe[IO, Boolean]
    val buffer = mutable.Buffer[String]()
    response
      .split()
      .body()
      .subscribe(new Subscriber[HttpData] {
        override def onSubscribe(s: Subscription): Unit = s.request(Long.MaxValue)

        override def onNext(t: HttpData): Unit =
          buffer += t.toStringUtf8

        override def onError(t: Throwable): Unit = {}

        override def onComplete(): Unit =
          deferred.complete(true).void.unsafeRunSync()
      })

    for {
      _ <- deferred.get
      _ <- assertIO(IO(buffer.mkString("")), "123456789")
    } yield ()
  }

  private def postLargeBody(path: String, body: String): Int = {
    val url = new URL(s"http://127.0.0.1:${httpPort.get}$path")
    val conn = url.openConnection().asInstanceOf[HttpURLConnection]
    val bytes = body.getBytes(StandardCharsets.UTF_8)
    conn.setRequestMethod("POST")
    conn.setRequestProperty("Content-Type", "text/html; charset=utf-8")
    conn.setDoOutput(true)
    conn.getOutputStream.write(bytes)
    val code = conn.getResponseCode
    conn.disconnect()
    code
  }

  private def postChunkedMultipart(path: String, boundary: String, body: String): String = {
    val url = new URL(s"http://127.0.0.1:${httpPort.get}$path")
    val conn = url.openConnection().asInstanceOf[HttpURLConnection]
    val bytes = body.getBytes(StandardCharsets.UTF_8)
    conn.setRequestMethod("POST")
    conn.setChunkedStreamingMode(-1)
    conn.setRequestProperty("Content-Type", s"""multipart/form-data; boundary="$boundary"""")
    conn.setDoOutput(true)
    conn.getOutputStream.write(bytes)
    Source.fromInputStream(conn.getInputStream, StandardCharsets.UTF_8.name).getLines().mkString
  }
}
