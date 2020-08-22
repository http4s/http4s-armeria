/*
 * Copyright 2013-2020 http4s.org
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package org.http4s
package armeria
package client

import cats.effect.{Bracket, ConcurrentEffect, ContextShift, Resource}
import cats.implicits._
import com.linecorp.armeria.client.WebClient
import com.linecorp.armeria.common.{
  HttpData,
  HttpHeaders,
  HttpMethod,
  HttpRequest,
  HttpResponse,
  RequestHeaders
}
import fs2.interop.reactivestreams._
import fs2.{Chunk, Stream}
import org.http4s.client.DefaultClient
import org.reactivestreams.Publisher
import org.http4s.internal.CollectionCompat.CollectionConverters._

private[armeria] final class ArmeriaClient[F[_]] private[client] (
    private val client: WebClient
)(implicit val B: Bracket[F, Throwable], F: ConcurrentEffect[F], C: ContextShift[F])
    extends DefaultClient[F] {

  override def run(req: Request[F]): Resource[F, Response[F]] =
    Resource.liftF(toResponse(client.execute(toHttpRequest(req))))

  /** Converts http4s' [[Request]] to http4s' [[com.linecorp.armeria.common.HttpRequest]]. */
  private def toHttpRequest(req: Request[F]): HttpRequest = {
    val requestHeaders = toRequestHeaders(req)

    if (req.body == EmptyBody)
      HttpRequest.of(requestHeaders)
    else {
      val body: Publisher[HttpData] = req.body.chunks.map { chunk =>
        val bytes = chunk.toBytes
        HttpData.copyOf(bytes.values, bytes.offset, bytes.length)
      }.toUnicastPublisher
      HttpRequest.of(requestHeaders, body)
    }
  }

  /** Converts http4s' [[Request]] to http4s' [[com.linecorp.armeria.common.ResponseHeaders]]. */
  private def toRequestHeaders(req: Request[F]): RequestHeaders = {
    val builder = RequestHeaders.builder(HttpMethod.valueOf(req.method.name), req.uri.renderString)
    req.headers.foreach { header =>
      val _ = builder.add(header.name.value, header.value)
    }
    builder.build()
  }

  /** Converts Armeria's [[com.linecorp.armeria.common.HttpResponse]] to http4s' [[Response]]. */
  private def toResponse(response: HttpResponse): F[Response[F]] = {
    val responseSubscriber = new ResponseSubscriber(response)
    for {
      headers <- responseSubscriber.headers
      status <- F.fromEither(Status.fromInt(headers.status().code()))
      bodyPublisher <- responseSubscriber.bodyPublisher
      body =
        bodyPublisher.toStream
          .collect { case x: HttpData => Chunk.bytes(x.array()) }
          .flatMap(Stream.chunk(_))
    } yield Response(status = status, headers = toHeaders(headers), body = body)
  }

  /** Converts Armeria's [[com.linecorp.armeria.common.HttpHeaders]] to http4s' [[Headers]]. */
  private def toHeaders(req: HttpHeaders): Headers =
    Headers(
      req.asScala
        .map(entry => Header(entry.getKey.toString(), entry.getValue))
        .toList
    )
}

object ArmeriaClient {
  def apply[F[_]](client: WebClient = WebClient.of())(implicit
      B: Bracket[F, Throwable],
      F: ConcurrentEffect[F],
      C: ContextShift[F]): ArmeriaClient[F] = new ArmeriaClient(client)(B, F, C)
}
