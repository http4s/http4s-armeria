/*
 * Copyright 2013-2020 http4s.org
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package org.http4s
package armeria
package client

import cats.effect.Resource
import cats.implicits._
import com.linecorp.armeria.client.WebClient
import com.linecorp.armeria.common.{
  HttpData,
  HttpHeaders,
  HttpMethod,
  HttpRequest,
  HttpResponse,
  RequestHeaders,
  ResponseHeaders
}
import fs2.interop.reactivestreams._
import fs2.{Chunk, Stream}
import java.util.concurrent.CompletableFuture

import cats.effect.kernel.{Async, MonadCancel}
import org.http4s.client.Client
import org.http4s.internal.CollectionCompat.CollectionConverters._
import org.typelevel.ci.CIString

private[armeria] final class ArmeriaClient[F[_]] private[client] (
    private val client: WebClient
)(implicit val B: MonadCancel[F, Throwable], F: Async[F]) {

  def run(req: Request[F]): Resource[F, Response[F]] =
    toHttpRequest(req).map(client.execute).flatMap(r => Resource.eval(toResponse(r)))

  /** Converts http4s' [[Request]] to http4s' [[com.linecorp.armeria.common.HttpRequest]]. */
  private def toHttpRequest(req: Request[F]): Resource[F, HttpRequest] = {
    val requestHeaders = toRequestHeaders(req)

    if (req.body == EmptyBody)
      Resource.pure(HttpRequest.of(requestHeaders))
    else {
      req.body.chunks
        .map { chunk =>
          val bytes = chunk.toArraySlice
          HttpData.copyOf(bytes.values, bytes.offset, bytes.length)
        }
        .toUnicastPublisher
        .map { body =>
          HttpRequest.of(requestHeaders, body)
        }
    }
  }

  /** Converts http4s' [[Request]] to http4s' [[com.linecorp.armeria.common.ResponseHeaders]]. */
  private def toRequestHeaders(req: Request[F]): RequestHeaders = {
    val builder = RequestHeaders.builder(HttpMethod.valueOf(req.method.name), req.uri.renderString)
    req.headers.foreach { header =>
      val _ = builder.add(header.name.toString, header.value)
    }
    builder.build()
  }

  /** Converts Armeria's [[com.linecorp.armeria.common.HttpResponse]] to http4s' [[Response]]. */
  private def toResponse(response: HttpResponse): F[Response[F]] = {
    val splitResponse = response.split()
    for {
      headers <- fromCompletableFuture(splitResponse.headers)
      status <- F.fromEither(Status.fromInt(headers.status().code()))
      body =
        splitResponse
          .body()
          .toStreamBuffered[F](1)
          .flatMap(x => Stream.chunk(Chunk.array(x.array())))
    } yield Response(status = status, headers = toHeaders(headers), body = body)
  }

  /** Converts [[java.util.concurrent.CompletableFuture]] to `F[_]` */
  private def fromCompletableFuture(
      completableFuture: CompletableFuture[ResponseHeaders]): F[ResponseHeaders] =
    F.async_[ResponseHeaders] { cb =>
      val _ = completableFuture.handle { (result, ex) =>
        if (ex != null)
          cb(Left(ex))
        else
          cb(Right(result))
        null
      }
    }

  /** Converts Armeria's [[com.linecorp.armeria.common.HttpHeaders]] to http4s' [[Headers]]. */
  private def toHeaders(req: HttpHeaders): Headers =
    Headers(
      req.asScala
        .map(entry => Header.Raw(CIString(entry.getKey.toString()), entry.getValue))
        .toList
    )
}

object ArmeriaClient {
  def apply[F[_]](client: WebClient = WebClient.of())(implicit F: Async[F]): Client[F] =
    Client(new ArmeriaClient(client).run)
}
