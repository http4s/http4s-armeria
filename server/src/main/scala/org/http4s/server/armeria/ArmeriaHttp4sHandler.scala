/*
 * Copyright 2013-2020 http4s.org
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package org.http4s
package server
package armeria

import cats.effect.{ConcurrentEffect, IO}
import cats.implicits._
import com.linecorp.armeria.common.{
  HttpData,
  HttpHeaderNames,
  HttpRequest,
  HttpResponse,
  ResponseHeaders
}
import com.linecorp.armeria.common.util.Version
import com.linecorp.armeria.server.{HttpService, ServiceRequestContext}
import io.chrisdavenport.vault.{Vault, Key => VaultKey}
import fs2._
import fs2.interop.reactivestreams._
import java.net.InetSocketAddress
import java.util.concurrent.CompletableFuture
import org.http4s.internal.CollectionCompat.CollectionConverters._
import org.http4s.internal.unsafeRunAsync
import org.http4s.server.armeria.ArmeriaHttp4sHandler.defaultVault
import scala.concurrent.ExecutionContext
import scodec.bits.ByteVector

/** An [[HttpService]] that handles the specified [[HttpApp]] under the specified `prefix`. */
private[armeria] class ArmeriaHttp4sHandler[F[_]](
    prefix: String,
    service: HttpApp[F],
    serviceErrorHandler: ServiceErrorHandler[F]
)(implicit F: ConcurrentEffect[F])
    extends HttpService {

  // micro-optimization: unwrap the service and call its .run directly
  private val serviceFn: Request[F] => F[Response[F]] = service.run

  override def serve(ctx: ServiceRequestContext, req: HttpRequest): HttpResponse = {
    implicit val ec = ExecutionContext.fromExecutor(ctx.eventLoop())
    val future = new CompletableFuture[HttpResponse]()
    unsafeRunAsync(toRequest(ctx, req).fold(onParseFailure, handleRequest)) {
      case Right(res) =>
        IO.pure(discardReturn(future.complete(res)))
      case Left(ex) =>
        IO.pure(discardReturn(future.completeExceptionally(ex)))
    }
    HttpResponse.from(future)
  }

  private def handleRequest(request: Request[F]): F[HttpResponse] =
    serviceFn(request)
      .recoverWith(serviceErrorHandler(request))
      .map(toHttpResponse)

  private def onParseFailure(parseFailure: ParseFailure): F[HttpResponse] = {
    val response = Response[F](Status.BadRequest).withEntity(parseFailure.sanitized)
    F.pure(toHttpResponse(response))
  }

  /** Converts http4s' [[Response]] to Armeria's [[HttpResponse]]. */
  private def toHttpResponse(response: Response[F]): HttpResponse = {
    val headers = Stream(toResponseHeaders(response.headers, response.status.some))
    val body: Stream[F, HttpData] = response.body.chunks.map { chunk =>
      val bytes = chunk.toBytes
      HttpData.copyOf(bytes.values, bytes.offset, bytes.length)
    }
    val trailers = Stream
      .eval(response.trailerHeaders)
      .flatMap { trailers =>
        if (trailers.isEmpty)
          Stream.empty
        else
          Stream(toResponseHeaders(trailers, None))
      }

    HttpResponse.of((headers ++ body ++ trailers).toUnicastPublisher())
  }

  /** Converts Armeria's [[HttpRequest]] to http4s' [[Request]]. */
  private def toRequest(ctx: ServiceRequestContext, req: HttpRequest): ParseResult[Request[F]] = {
    val path = req.path()
    for {
      method <- Method.fromString(req.method().name())
      uri <- Uri.requestTarget(path)
    } yield Request(
      method = method,
      uri = uri,
      httpVersion =
        if (ctx.sessionProtocol().isMultiplex)
          HttpVersion.`HTTP/2.0`
        else if (req.headers().get(HttpHeaderNames.HOST) != null)
          HttpVersion.`HTTP/1.1`
        else
          HttpVersion.`HTTP/1.0`,
      headers = toHeaders(req),
      body = toBody(req),
      attributes = requestAttributes(ctx)
    )
  }

  /** Converts http4s' [[Headers]] to Armeria's [[ResponseHeaders]]. */
  private def toResponseHeaders(headers: Headers, status: Option[Status]): ResponseHeaders = {
    val builder = status.fold(ResponseHeaders.builder())(s => ResponseHeaders.builder(s.code))

    for (header <- headers.toList)
      builder.add(header.name.toString, header.value)
    builder.build()
  }

  /** Converts Armeria's [[HttpRequest]] to htt4s' [[Headers]]. */
  private def toHeaders(req: HttpRequest): Headers =
    Headers(
      req
        .headers()
        .asScala
        .map(entry => Header(entry.getKey.toString(), entry.getValue))
        .toList
    )

  /** Converts an HTTP payload to [[EntityBody]]. */
  private def toBody(req: HttpRequest): EntityBody[F] =
    req
      .toStream[F]
      .collect { case x: HttpData => Chunk.bytes(x.array()) }
      .flatMap(Stream.chunk)

  private def requestAttributes(ctx: ServiceRequestContext): Vault = {
    val secure = ctx.sessionProtocol().isTls
    defaultVault
      .insert(Request.Keys.PathInfoCaret, prefix.length)
      .insert(ServiceRequestContexts.Key, ctx)
      .insert(
        Request.Keys.ConnectionInfo,
        Request.Connection(
          local = ctx.localAddress[InetSocketAddress],
          remote = ctx.remoteAddress[InetSocketAddress],
          secure = secure)
      )
      .insert(
        ServerRequestKeys.SecureSession,
        if (secure) {
          val sslSession = ctx.sslSession()
          val cipherSuite = sslSession.getCipherSuite
          Some(
            SecureSession(
              ByteVector(sslSession.getId).toHex,
              cipherSuite,
              SSLContextFactory.deduceKeyLength(cipherSuite),
              SSLContextFactory.getCertChain(sslSession)
            ))
        } else
          None
      )
  }

  /** Discards the returned value from the specified `f` and return [[Unit]].
    * A work around for "discarded non-Unit value" error on Java [[Void]] type.
    */
  @inline
  private def discardReturn(f: => Any): Unit = {
    val _ = f
  }
}

private[armeria] object ArmeriaHttp4sHandler {
  def apply[F[_]: ConcurrentEffect](prefix: String, service: HttpApp[F]): ArmeriaHttp4sHandler[F] =
    new ArmeriaHttp4sHandler(prefix, service, DefaultServiceErrorHandler)

  private val serverSoftware: ServerSoftware =
    ServerSoftware("armeria", Some(Version.get("armeria").artifactVersion()))

  private val defaultVault: Vault = Vault.empty.insert(Request.Keys.ServerSoftware, serverSoftware)
}

object ServiceRequestContexts {
  val Key: VaultKey[ServiceRequestContext] =
    VaultKey.newKey[IO, ServiceRequestContext].unsafeRunSync
}
