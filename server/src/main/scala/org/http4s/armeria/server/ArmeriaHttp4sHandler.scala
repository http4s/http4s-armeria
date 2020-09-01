/*
 * Copyright 2013-2020 http4s.org
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package org.http4s
package armeria
package server

import cats.effect.{ConcurrentEffect, IO}
import cats.implicits._
import com.linecorp.armeria.common.{
  HttpData,
  HttpHeaderNames,
  HttpHeaders,
  HttpMethod,
  HttpRequest,
  HttpResponse,
  HttpResponseWriter,
  ResponseHeaders
}
import com.linecorp.armeria.common.util.Version
import com.linecorp.armeria.server.{HttpService, ServiceRequestContext}
import io.chrisdavenport.vault.{Vault, Key => VaultKey}
import fs2._
import fs2.interop.reactivestreams._
import java.net.InetSocketAddress
import org.http4s.internal.CollectionCompat.CollectionConverters._
import ArmeriaHttp4sHandler.{RightUnit, defaultVault, toHttp4sMethod}
import org.http4s.internal.unsafeRunAsync
import org.http4s.server.{
  DefaultServiceErrorHandler,
  SecureSession,
  ServerRequestKeys,
  ServiceErrorHandler
}
import scala.concurrent.ExecutionContext
import scodec.bits.ByteVector

/** An [[HttpService]] that handles the specified [[HttpApp]] under the specified `prefix`. */
private[armeria] class ArmeriaHttp4sHandler[F[_]](
    prefix: String,
    service: HttpApp[F],
    serviceErrorHandler: ServiceErrorHandler[F]
)(implicit F: ConcurrentEffect[F])
    extends HttpService {

  val prefixLength = if (prefix.endsWith("/")) prefix.length - 1 else prefix.length
  // micro-optimization: unwrap the service and call its .run directly
  private val serviceFn: Request[F] => F[Response[F]] = service.run

  override def serve(ctx: ServiceRequestContext, req: HttpRequest): HttpResponse = {
    implicit val ec = ExecutionContext.fromExecutor(ctx.eventLoop())
    val responseWriter = HttpResponse.streaming()
    unsafeRunAsync(
      toRequest(ctx, req)
        .fold(onParseFailure(_, responseWriter), handleRequest(_, responseWriter))) {
      case Right(_) =>
        IO.unit
      case Left(ex) =>
        discardReturn(responseWriter.close(ex))
        IO.unit
    }
    responseWriter
  }

  private def handleRequest(request: Request[F], writer: HttpResponseWriter): F[Unit] =
    serviceFn(request)
      .recoverWith(serviceErrorHandler(request))
      .flatMap(toHttpResponse(_, writer))

  private def onParseFailure(parseFailure: ParseFailure, writer: HttpResponseWriter): F[Unit] = {
    val response = Response[F](Status.BadRequest).withEntity(parseFailure.sanitized)
    toHttpResponse(response, writer)
  }

  /** Converts http4s' [[Response]] to Armeria's [[HttpResponse]]. */
  private def toHttpResponse(response: Response[F], writer: HttpResponseWriter): F[Unit] = {
    val headers = toHttpHeaders(response.headers, response.status.some)
    writer.write(headers)
    val body = response.body
    if (body == EmptyBody) {
      writer.close()
      F.unit
    } else
      writeOnDemand(writer, body).stream
        .onFinalize(maybeWriteTrailersAndClose(writer, response))
        .compile
        .drain
  }

  private def maybeWriteTrailersAndClose(writer: HttpResponseWriter, response: Response[F]) =
    response.trailerHeaders.map { trailers =>
      if (!trailers.isEmpty)
        writer.write(toHttpHeaders(trailers, None))
      writer.close()
    }

  private def writeOnDemand(
      writer: HttpResponseWriter,
      body: Stream[F, Byte]): Pull[F, INothing, Unit] =
    body.pull.uncons.flatMap {
      case Some((head, tail)) =>
        val bytes = head.toBytes
        writer.write(HttpData.wrap(bytes.values, bytes.offset, bytes.length))
        if (tail == Stream.empty)
          Pull.done
        else
          Pull.eval(F.async[Unit] { cb =>
            discardReturn(writer.whenConsumed().thenRun(() => cb(RightUnit)))
          }) >> writeOnDemand(writer, tail)
      case None =>
        Pull.done
    }

  /** Converts Armeria's [[HttpRequest]] to http4s' [[Request]]. */
  private def toRequest(ctx: ServiceRequestContext, req: HttpRequest): ParseResult[Request[F]] = {
    val path = req.path()
    for {
      method <- toHttp4sMethod(req.method())
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

  /** Converts http4s' [[Headers]] to Armeria's [[HttpHeaders]]. */
  private def toHttpHeaders(headers: Headers, status: Option[Status]): HttpHeaders = {
    val builder = status.fold(HttpHeaders.builder())(s => ResponseHeaders.builder(s.code))

    for (header <- headers.toList)
      builder.add(header.name.toString, header.value)
    builder.build()
  }

  /** Converts Armeria's [[com.linecorp.armeria.common.HttpHeaders]] to http4s' [[Headers]]. */
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
      .insert(Request.Keys.PathInfoCaret, prefixLength)
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

  private val OPTIONS: ParseResult[Method] = Right(Method.OPTIONS)
  private val GET: ParseResult[Method] = Right(Method.GET)
  private val HEAD: ParseResult[Method] = Right(Method.HEAD)
  private val POST: ParseResult[Method] = Right(Method.POST)
  private val PUT: ParseResult[Method] = Right(Method.PUT)
  private val PATCH: ParseResult[Method] = Right(Method.PATCH)
  private val DELETE: ParseResult[Method] = Right(Method.DELETE)
  private val TRACE: ParseResult[Method] = Right(Method.TRACE)
  private val CONNECT: ParseResult[Method] = Right(Method.CONNECT)

  private val RightUnit = Right(())

  private def toHttp4sMethod(method: HttpMethod): ParseResult[Method] =
    method match {
      case HttpMethod.OPTIONS => OPTIONS
      case HttpMethod.GET => GET
      case HttpMethod.HEAD => HEAD
      case HttpMethod.POST => POST
      case HttpMethod.PUT => PUT
      case HttpMethod.PATCH => PATCH
      case HttpMethod.DELETE => DELETE
      case HttpMethod.TRACE => TRACE
      case HttpMethod.CONNECT => CONNECT
      case HttpMethod.UNKNOWN => Left(ParseFailure("Invalid method", method.name()))
    }
}

object ServiceRequestContexts {
  val Key: VaultKey[ServiceRequestContext] =
    VaultKey.newKey[IO, ServiceRequestContext].unsafeRunSync()
}
