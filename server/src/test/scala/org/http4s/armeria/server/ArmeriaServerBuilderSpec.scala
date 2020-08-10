/*
 * Copyright 2013-2020 http4s.org
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package org.http4s.armeria.server

import cats.effect.IO
import cats.implicits._
import com.linecorp.armeria.client.{ClientFactory, WebClient}
import com.linecorp.armeria.client.logging.LoggingClient
import com.linecorp.armeria.common.HttpStatus
import com.linecorp.armeria.server.logging.{ContentPreviewingService, LoggingService}
import java.net.{HttpURLConnection, URL}
import java.nio.charset.StandardCharsets
import org.http4s.HttpRoutes
import org.http4s.dsl.io._
import org.http4s.multipart.Multipart
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.must.Matchers
import scala.concurrent.duration._
import scala.io.Source

class ArmeriaServerBuilderSpec extends AnyFunSuite with IOServerFixture with Matchers {

  val service: HttpRoutes[IO] = HttpRoutes.of {
    case GET -> Root / "thread" / "routing" =>
      val thread = Thread.currentThread.getName
      Ok(thread)

    case GET -> Root / "thread" / "effect" =>
      IO(Thread.currentThread.getName).flatMap(Ok(_))

    case req @ POST -> Root / "echo" =>
      Ok(req.body)

    case _ -> Root / "never" =>
      IO.never

    case req @ POST -> Root / "issue2610" =>
      req.decode[Multipart[IO]] { mp =>
        Ok(mp.parts.foldMap(_.body))
      }

    case _ => NotFound()
  }

  protected def configureServer(serverBuilder: ArmeriaServerBuilder[IO]): ArmeriaServerBuilder[IO] =
    serverBuilder
      .withDecorator(ContentPreviewingService.newDecorator(Int.MaxValue))
      .withDecorator(LoggingService.newDecorator())
      .bindAny()
      .withRequestTimeout(10.seconds)
      .withGracefulShutdownTimeout(0.seconds, 0.seconds)
      .withHttpRoutes("/service", service)

  lazy val client: WebClient = WebClient
    .builder(s"http://127.0.0.1:${httpPort.get}")
    .decorator(LoggingClient.newDecorator())
    .build()

  test("route requests on the service executor") {
    // A event loop will serve the service to reduce an extra context switching
    client
      .get("/service/thread/routing")
      .aggregate()
      .join()
      .contentUtf8() must startWith("armeria-common-worker-nio")
  }

  test("execute the service task on the service executor") {
    // A event loop will serve the service to reduce an extra context switching
    client
      .get("/service/thread/effect")
      .aggregate()
      .join()
      .contentUtf8() must startWith("armeria-common-worker-nio")
  }

  test("be able to echo its input") {
    val input = """{ "Hello": "world" }"""
    client
      .post("/service/echo", input)
      .aggregate()
      .join()
      .contentUtf8() must startWith(input)
  }

  test("return a 503 if the server doesn't respond") {
    val noTimeoutClient = WebClient
      .builder(s"http://127.0.0.1:${httpPort.get}")
      .factory(ClientFactory.builder().idleTimeoutMillis(0).build())
      .responseTimeoutMillis(0)
      .decorator(LoggingClient.newDecorator())
      .build()
    noTimeoutClient.get("/service/never").aggregate().join().status() must be(
      HttpStatus.SERVICE_UNAVAILABLE)
  }

  test("reliably handle multipart requests") {
    val body =
      """|--aa
         |Content-Disposition: form-data; name="a"
         |Content-Length: 1
         |
         |a
         |--aa--""".stripMargin.replace("\n", "\r\n")

    postChunkedMultipart("/service/issue2610", "aa", body) must be("a")
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
    Source.fromInputStream(conn.getInputStream, StandardCharsets.UTF_8.name).getLines.mkString
  }
}
