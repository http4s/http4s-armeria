/*
 * Copyright 2020 Ikhun
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
import ArmeriaHttp4sHandler.{RightUnit, canHasBody, defaultVault, toHttp4sMethod}
import org.http4s.server.{
  DefaultServiceErrorHandler,
  SecureSession,
  ServerRequestKeys,
  ServiceErrorHandler
}
import scodec.bits.ByteVector

/** An [[HttpService]] that handles the specified [[HttpApp]] under the specified `prefix`. */
private[armeria] class ArmeriaHttp4sHandler[F[_]](
    prefix: String,
    service: HttpApp[F],
    serviceErrorHandler: ServiceErrorHandler[F]
)(implicit F: ConcurrentEffect[F])
    extends HttpService {

  val prefixLength: Int = if (prefix.endsWith("/")) prefix.length - 1 else prefix.length
  // micro-optimization: unwrap the service and call its .run directly
  private val serviceFn: Request[F] => F[Response[F]] = service.run

  override def serve(ctx: ServiceRequestContext, req: HttpRequest): HttpResponse = {
    val responseWriter = HttpResponse.streaming()
    F.runAsync(
      toRequest(ctx, req)
        .fold(onParseFailure(_, responseWriter), handleRequest(_, responseWriter))) {
      case Right(_) =>
        IO.unit
      case Left(ex) =>
        discardReturn(responseWriter.close(ex))
        IO.unit
    }.unsafeRunSync()
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
    } else if (response.contentLength.isDefined)
      // non stream response
      response.body.chunks.compile.toVector
        .flatMap { vector =>
          vector.foreach { chunk =>
            val bytes = chunk.toBytes
            writer.write(HttpData.wrap(bytes.values, bytes.offset, bytes.length))
          }
          maybeWriteTrailersAndClose(writer, response)
        }
    else
      writeOnDemand(writer, body).stream
        .onFinalize(maybeWriteTrailersAndClose(writer, response))
        .compile
        .drain
  }

  private def maybeWriteTrailersAndClose(
      writer: HttpResponseWriter,
      response: Response[F]): F[Unit] =
    response.trailerHeaders.map { trailers =>
      if (trailers.nonEmpty)
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
  private def toHttpHeaders(headers: Headers, status: Option[Status]): HttpHeaders =
    if (headers.isEmpty)
      status.fold(HttpHeaders.of())(s => ResponseHeaders.of(s.code))
    else {
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
    if (canHasBody(req.method()))
      req
        .toStream[F]
        .flatMap { obj =>
          val data = obj.asInstanceOf[HttpData]
          Stream.chunk(Chunk.bytes(data.array()))
        }
    else
      EmptyBody

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

  private def canHasBody(method: HttpMethod): Boolean =
    method match {
      case HttpMethod.OPTIONS => false
      case HttpMethod.GET => false
      case HttpMethod.HEAD => false
      case HttpMethod.TRACE => false
      case HttpMethod.CONNECT => false
      case HttpMethod.POST => true
      case HttpMethod.PUT => true
      case HttpMethod.PATCH => true
      case HttpMethod.DELETE => true
      case HttpMethod.UNKNOWN => false
    }
}

object ServiceRequestContexts {
  val Key: VaultKey[ServiceRequestContext] =
    VaultKey.newKey[IO, ServiceRequestContext].unsafeRunSync()
}
