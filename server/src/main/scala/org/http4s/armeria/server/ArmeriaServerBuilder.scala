/*
 * Copyright 2013-2020 http4s.org
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package org.http4s.armeria.server

import cats.effect.{ConcurrentEffect, Resource}
import cats.implicits._
import com.linecorp.armeria.common.util.Version
import com.linecorp.armeria.common.{HttpRequest, HttpResponse, SessionProtocol}
import com.linecorp.armeria.server.{
  HttpService,
  HttpServiceWithRoutes,
  ServerListenerAdapter,
  ServiceRequestContext,
  Server => BackendServer,
  ServerBuilder => ArmeriaBuilder
}
import io.micrometer.core.instrument.MeterRegistry
import io.netty.channel.ChannelOption
import io.netty.handler.ssl.SslContextBuilder
import java.io.{File, InputStream}
import java.net.InetSocketAddress
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.util.function.{Function => JFunction}
import javax.net.ssl.KeyManagerFactory
import org.http4s.{BuildInfo, HttpApp, HttpRoutes}
import org.http4s.server.{
  DefaultServiceErrorHandler,
  Server,
  ServerBuilder,
  ServiceErrorHandler,
  defaults
}
import org.http4s.server.defaults.{IdleTimeout, ResponseTimeout, ShutdownTimeout}
import org.http4s.syntax.all._
import org.log4s.{Logger, getLogger}
import scala.collection.immutable
import scala.concurrent.duration.FiniteDuration

sealed class ArmeriaServerBuilder[F[_]] private (
    armeriaServerBuilder: ArmeriaBuilder,
    socketAddress: InetSocketAddress,
    serviceErrorHandler: ServiceErrorHandler[F],
    banner: List[String]
)(implicit protected val F: ConcurrentEffect[F])
    extends ServerBuilder[F] {
  override type Self = ArmeriaServerBuilder[F]

  private[this] val logger: Logger = getLogger

  type DecoratingFunction = (HttpService, ServiceRequestContext, HttpRequest) => HttpResponse

  override def bindSocketAddress(socketAddress: InetSocketAddress): Self =
    copy(socketAddress = socketAddress)

  override def withServiceErrorHandler(serviceErrorHandler: ServiceErrorHandler[F]): Self =
    copy(serviceErrorHandler = serviceErrorHandler)

  override def resource: Resource[F, ArmeriaServer[F]] =
    Resource(F.delay {
      val armeriaServer0 = armeriaServerBuilder
        .http(socketAddress)
        .build()

      armeriaServer0.addListener(new ServerListenerAdapter {
        override def serverStarting(server: BackendServer): Unit = {
          banner.foreach(logger.info(_))

          val armeriaVersion = Version.get("armeria").artifactVersion()

          logger.info(s"http4s v${BuildInfo.version} on Armeria v${armeriaVersion} started")
        }
      })
      armeriaServer0.start().join()

      val armeriaServer: ArmeriaServer[F] = new ArmeriaServer[F] {
        lazy val address: InetSocketAddress = {
          val host = socketAddress.getHostString
          val port = server.activeLocalPort()
          new InetSocketAddress(host, port)
        }

        lazy val server: BackendServer = armeriaServer0
        lazy val isSecure: Boolean = server.activePort(SessionProtocol.HTTPS) != null
      }

      armeriaServer -> shutdown(armeriaServer.server)
    })

  /** Binds the specified `service` at the specified path pattern.
    * See [[https://armeria.dev/docs/server-basics#path-patterns]] for detailed information of path pattens.
    */
  def withHttpService(
      pathPattern: String,
      service: (ServiceRequestContext, HttpRequest) => HttpResponse): Self = {
    armeriaServerBuilder.service(
      pathPattern,
      new HttpService {
        override def serve(ctx: ServiceRequestContext, req: HttpRequest): HttpResponse =
          service(ctx, req)
      })
    this
  }

  /** Binds the specified [[com.linecorp.armeria.server.HttpService]] at the specified path pattern.
    * See [[https://armeria.dev/docs/server-basics#path-patterns]] for detailed information of path pattens.
    */
  def withHttpService(pathPattern: String, service: HttpService): Self = {
    armeriaServerBuilder.service(pathPattern, service)
    this
  }

  /** Binds the specified [[com.linecorp.armeria.server.HttpServiceWithRoutes]]
    * at multiple [[com.linecorp.armeria.server.Route]]s
    * of the default [[com.linecorp.armeria.server.VirtualHost]].
    */
  def withHttpService(serviceWithRoutes: HttpServiceWithRoutes): Self = {
    armeriaServerBuilder.service(serviceWithRoutes)
    this
  }

  /** Binds the specified [[com.linecorp.armeria.server.HttpService]] under the specified directory.
    */
  def withHttpServiceUnder(prefix: String, service: HttpService): Self = {
    armeriaServerBuilder.serviceUnder(prefix, service)
    this
  }

  /** Binds the specified [[org.http4s.HttpRoutes]] under the specified prefix. */
  def withHttpRoutes(prefix: String, service: HttpRoutes[F]): Self =
    withHttpApp(prefix, service.orNotFound)

  /** Binds the specified [[org.http4s.HttpApp]] under the specified prefix. */
  def withHttpApp(prefix: String, service: HttpApp[F]): Self = {
    armeriaServerBuilder.serviceUnder(prefix, ArmeriaHttp4sHandler(prefix, service))
    this
  }

  /** Decorates all HTTP services with the specified [[DecoratingFunction]]. */
  def withDecorator(decorator: DecoratingFunction): Self = {
    armeriaServerBuilder.decorator((delegate, ctx, req) => decorator(delegate, ctx, req))
    this
  }

  /** Decorates all HTTP services with the specified `decorator`. */
  def withDecorator(decorator: JFunction[_ >: HttpService, _ <: HttpService]): Self = {
    armeriaServerBuilder.decorator(decorator)
    this
  }

  /** Decorates HTTP services under the specified directory with the specified [[DecoratingFunction]]. */
  def withDecoratorUnder(prefix: String, decorator: DecoratingFunction): Self = {
    armeriaServerBuilder.decoratorUnder(
      prefix,
      (delegate, ctx, req) => decorator(delegate, ctx, req))
    this
  }

  /** Decorates HTTP services under the specified directory with the specified `decorator`. */
  def withDecoratorUnder(
      prefix: String,
      decorator: JFunction[_ >: HttpService, _ <: HttpService]): Self = {
    armeriaServerBuilder.decoratorUnder(prefix, decorator)
    this
  }

  /** Configures the Armeria server using the specified [[com.linecorp.armeria.server.ServerBuilder]]. */
  def withArmeriaBuilder(customizer: ArmeriaBuilder => Unit): Self = {
    customizer(armeriaServerBuilder)
    this
  }

  /** Sets the idle timeout of a connection in milliseconds for keep-alive.
    *
    * @param idleTimeout the timeout. [[scala.concurrent.duration.Duration.Zero]] disables the timeout.
    */
  def withIdleTimeout(idleTimeout: FiniteDuration): Self = {
    armeriaServerBuilder.idleTimeoutMillis(idleTimeout.toMillis)
    this
  }

  /** Sets the timeout of a request.
    *
    * @param requestTimeout the timeout. [[scala.concurrent.duration.Duration.Zero]] disables the timeout.
    */
  def withRequestTimeout(requestTimeout: FiniteDuration): Self = {
    armeriaServerBuilder.requestTimeoutMillis(requestTimeout.toMillis)
    this
  }

  /** Adds an HTTP port that listens on all available network interfaces.
    *
    * @param port the HTTP port number.
    * @see [[com.linecorp.armeria.server.ServerBuilder#https(localAddress:java\.net\.InetSocketAddress):com\.linecorp\.armeria\.server\.ServerBuilder*]]
    */
  def withHttp(port: Int): Self = {
    armeriaServerBuilder.http(port)
    this
  }

  /** Adds an HTTPS port that listens on all available network interfaces.
    *
    * @param port the HTTPS port number.
    * @see [[com.linecorp.armeria.server.ServerBuilder#https(localAddress:java\.net\.InetSocketAddress):com\.linecorp\.armeria\.server\.ServerBuilder*]]
    */
  def withHttps(port: Int): Self = {
    armeriaServerBuilder.https(port)
    this
  }

  /** Sets the [[io.netty.channel.ChannelOption]] of the server socket bound by
    * [[com.linecorp.armeria.server.Server]].
    * Note that the previously added option will be overridden if the same option is set again.
    *
    * @see [[https://armeria.dev/docs/advanced-production-checklist Production checklist]]
    */
  def withChannelOption[T](option: ChannelOption[T], value: T): Self = {
    armeriaServerBuilder.channelOption(option, value)
    this
  }

  /** Sets the [[io.netty.channel.ChannelOption]] of sockets accepted by [[com.linecorp.armeria.server.Server]].
    * Note that the previously added option will be overridden if the same option is set again.
    *
    * @see [[https://armeria.dev/docs/advanced-production-checklist Production checklist]]
    */
  def withChildChannelOption[T](option: ChannelOption[T], value: T): Self = {
    armeriaServerBuilder.childChannelOption(option, value)
    this
  }

  /** Configures SSL or TLS of this [[com.linecorp.armeria.server.Server]] from the specified
    * `keyCertChainFile`, `keyFile` and `keyPassword`.
    *
    * @see [[withTlsCustomizer]]
    */
  def withTls(keyCertChainFile: File, keyFile: File, keyPassword: Option[String]): Self = {
    armeriaServerBuilder.tls(keyCertChainFile, keyFile, keyPassword.orNull)
    this
  }

  /** Configures SSL or TLS of this [[com.linecorp.armeria.server.Server]] with the specified
    * `keyCertChainInputStream`, `keyInputStream` and `keyPassword`.
    *
    * @see [[withTlsCustomizer]]
    */
  def withTls(
      keyCertChainInputStream: Resource[F, InputStream],
      keyInputStream: Resource[F, InputStream],
      keyPassword: Option[String]): F[Self] =
    (keyCertChainInputStream, keyInputStream).tupled
      .use {
        case (keyCertChain, key) =>
          F.delay {
            armeriaServerBuilder.tls(keyCertChain, key, keyPassword.orNull)
            this
          }
      }

  /** Configures SSL or TLS of this [[com.linecorp.armeria.server.Server]] with the specified cleartext
    * [[java.security.PrivateKey]] and [[java.security.cert.X509Certificate]] chain.
    *
    * @see [[withTlsCustomizer]]
    */
  def withTls(key: PrivateKey, keyCertChain: X509Certificate*): Self = {
    armeriaServerBuilder.tls(key, keyCertChain: _*)
    this
  }

  /** Configures SSL or TLS of this [[com.linecorp.armeria.server.Server]] with the specified
    * [[javax.net.ssl.KeyManagerFactory]].
    *
    * @see [[withTlsCustomizer]]
    */
  def withTls(keyManagerFactory: KeyManagerFactory): Self = {
    armeriaServerBuilder.tls(keyManagerFactory)
    this
  }

  /** Adds the specified `tlsCustomizer` which can arbitrarily configure the
    * [[io.netty.handler.ssl.SslContextBuilder]] that will be applied to the SSL session.
    */
  def withTlsCustomizer(tlsCustomizer: SslContextBuilder => Unit): Self = {
    armeriaServerBuilder.tlsCustomizer(ctxBuilder => tlsCustomizer(ctxBuilder))
    this
  }

  /** Sets the amount of time to wait after calling [[com.linecorp.armeria.server.Server#stop]] for
    * requests to go away before actually shutting down.
    *
    * @param quietPeriod the number of milliseconds to wait for active
    *                    requests to go end before shutting down. [[scala.concurrent.duration.Duration.Zero]] means
    *                    the server will stop right away without waiting.
    * @param timeout     the amount of time to wait before shutting down the server regardless of active
    *                    requests.
    *                    This should be set to a time greater than `quietPeriod` to ensure the server
    *                    shuts down even if there is a stuck request.
    */
  def withGracefulShutdownTimeout(quietPeriod: FiniteDuration, timeout: FiniteDuration): Self = {
    armeriaServerBuilder.gracefulShutdownTimeoutMillis(quietPeriod.toMillis, timeout.toMillis)
    this
  }

  /** Sets the [[io.micrometer.core.instrument.MeterRegistry]] that collects various stats. */
  def withMeterRegistry(meterRegistry: MeterRegistry): Self = {
    armeriaServerBuilder.meterRegistry(meterRegistry)
    this
  }

  private def shutdown(armeriaServer: BackendServer): F[Unit] =
    F.async[Unit] { cb =>
      val _ = armeriaServer
        .stop()
        .whenComplete { (_, cause) =>
          if (cause == null)
            cb(Right(()))
          else
            cb(Left(cause))
        }
    }

  override def withBanner(banner: immutable.Seq[String]): Self = copy(banner = banner.toList)

  private def copy(
      armeriaServerBuilder: ArmeriaBuilder = armeriaServerBuilder,
      socketAddress: InetSocketAddress = socketAddress,
      serviceErrorHandler: ServiceErrorHandler[F] = serviceErrorHandler,
      banner: List[String] = banner
  ): Self =
    new ArmeriaServerBuilder(armeriaServerBuilder, socketAddress, serviceErrorHandler, banner)
}

// TODO(ikhoon): Hide `Server[F]` from public to avoid breaking changes in http4s v1.0
trait ArmeriaServer[F[_]] extends Server[F] {
  def server: BackendServer
}

/** A builder that builds Armeria server for Http4s. */
object ArmeriaServerBuilder {

  /** Returns a newly created [[org.http4s.armeria.server.ArmeriaServerBuilder]]. */
  def apply[F[_]: ConcurrentEffect]: ArmeriaServerBuilder[F] = {
    val defaultServerBuilder =
      BackendServer
        .builder()
        .idleTimeoutMillis(IdleTimeout.toMillis)
        .requestTimeoutMillis(ResponseTimeout.toMillis)
        .gracefulShutdownTimeoutMillis(ShutdownTimeout.toMillis, ShutdownTimeout.toMillis)
    new ArmeriaServerBuilder(
      armeriaServerBuilder = defaultServerBuilder,
      socketAddress = defaults.SocketAddress,
      serviceErrorHandler = DefaultServiceErrorHandler,
      banner = defaults.Banner)
  }
}
