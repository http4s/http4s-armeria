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

import cats.effect.{Async, Resource}
import cats.syntax.applicative._
import cats.syntax.flatMap._
import cats.syntax.functor._
import com.linecorp.armeria.common.util.Version
import com.linecorp.armeria.common.{HttpRequest, HttpResponse, SessionProtocol}
import com.linecorp.armeria.server.{
  HttpService,
  HttpServiceWithRoutes,
  Server => BackendServer,
  ServerBuilder => ArmeriaBuilder,
  ServerListenerAdapter,
  ServiceRequestContext
}
import io.micrometer.core.instrument.MeterRegistry
import io.netty.channel.ChannelOption
import io.netty.handler.ssl.SslContextBuilder
import java.io.{File, InputStream}
import java.net.InetSocketAddress
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.util.function.{Function => JFunction}

import cats.effect.std.Dispatcher
import javax.net.ssl.KeyManagerFactory
import org.http4s.armeria.server.ArmeriaServerBuilder.AddServices
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
    addServices: AddServices[F],
    socketAddress: InetSocketAddress,
    serviceErrorHandler: ServiceErrorHandler[F],
    banner: List[String])(implicit protected val F: Async[F])
    extends ServerBuilder[F] {
  override type Self = ArmeriaServerBuilder[F]

  type DecoratingFunction = (HttpService, ServiceRequestContext, HttpRequest) => HttpResponse

  private[this] val logger: Logger = getLogger

  override def bindSocketAddress(socketAddress: InetSocketAddress): Self =
    copy(socketAddress = socketAddress)

  override def withServiceErrorHandler(serviceErrorHandler: ServiceErrorHandler[F]): Self =
    copy(serviceErrorHandler = serviceErrorHandler)

  override def resource: Resource[F, ArmeriaServer] =
    Dispatcher[F].flatMap { dispatcher =>
      Resource(for {
        defaultServerBuilder <- F.delay {
          BackendServer
            .builder()
            .idleTimeoutMillis(IdleTimeout.toMillis)
            .requestTimeoutMillis(ResponseTimeout.toMillis)
            .gracefulShutdownTimeoutMillis(ShutdownTimeout.toMillis, ShutdownTimeout.toMillis)
        }
        builderWithServices <- addServices(defaultServerBuilder, dispatcher)
        res <- F.delay {
          val armeriaServer0 = builderWithServices.http(socketAddress).build()

          armeriaServer0.addListener(new ServerListenerAdapter {
            override def serverStarting(server: BackendServer): Unit = {
              banner.foreach(logger.info(_))

              val armeriaVersion = Version.get("armeria").artifactVersion()

              logger.info(s"http4s v${BuildInfo.version} on Armeria v$armeriaVersion started")
            }
          })
          armeriaServer0.start().join()

          val armeriaServer: ArmeriaServer = new ArmeriaServer {
            lazy val address: InetSocketAddress = {
              val host = socketAddress.getHostString
              val port = server.activeLocalPort()
              new InetSocketAddress(host, port)
            }

            lazy val server: BackendServer = armeriaServer0
            lazy val isSecure: Boolean = server.activePort(SessionProtocol.HTTPS) != null
          }

          armeriaServer -> shutdown(armeriaServer.server)
        }
      } yield res)
    }

  /** Binds the specified `service` at the specified path pattern. See
    * [[https://armeria.dev/docs/server-basics#path-patterns]] for detailed information of path
    * pattens.
    */
  def withHttpService(
      pathPattern: String,
      service: (ServiceRequestContext, HttpRequest) => HttpResponse): Self =
    atBuild(
      _.service(
        pathPattern,
        new HttpService {
          override def serve(ctx: ServiceRequestContext, req: HttpRequest): HttpResponse =
            service(ctx, req)
        }))

  /** Binds the specified [[com.linecorp.armeria.server.HttpService]] at the specified path pattern.
    * See [[https://armeria.dev/docs/server-basics#path-patterns]] for detailed information of path
    * pattens.
    */
  def withHttpService(pathPattern: String, service: HttpService): Self =
    atBuild(_.service(pathPattern, service))

  /** Binds the specified [[com.linecorp.armeria.server.HttpServiceWithRoutes]] at multiple
    * [[com.linecorp.armeria.server.Route]] s of the default
    * [[com.linecorp.armeria.server.VirtualHost]].
    */
  def withHttpService(serviceWithRoutes: HttpServiceWithRoutes): Self =
    atBuild(_.service(serviceWithRoutes))

  /** Binds the specified [[com.linecorp.armeria.server.HttpService]] under the specified directory.
    */
  def withHttpServiceUnder(prefix: String, service: HttpService): Self =
    atBuild(_.serviceUnder(prefix, service))

  /** Binds the specified [[org.http4s.HttpRoutes]] under the specified prefix. */
  def withHttpRoutes(prefix: String, service: HttpRoutes[F]): Self =
    withHttpApp(prefix, service.orNotFound)

  /** Binds the specified [[org.http4s.HttpApp]] under the specified prefix. */
  def withHttpApp(prefix: String, service: HttpApp[F]): Self =
    copy(addServices = (ab, dispatcher) =>
      addServices(ab, dispatcher).map(
        _.serviceUnder(prefix, ArmeriaHttp4sHandler(prefix, service, dispatcher))))

  /** Decorates all HTTP services with the specified [[DecoratingFunction]]. */
  def withDecorator(decorator: DecoratingFunction): Self =
    atBuild(_.decorator((delegate, ctx, req) => decorator(delegate, ctx, req)))

  /** Decorates all HTTP services with the specified `decorator`. */
  def withDecorator(decorator: JFunction[_ >: HttpService, _ <: HttpService]): Self =
    atBuild(_.decorator(decorator))

  /** Decorates HTTP services under the specified directory with the specified
    * [[DecoratingFunction]].
    */
  def withDecoratorUnder(prefix: String, decorator: DecoratingFunction): Self =
    atBuild(_.decoratorUnder(prefix, (delegate, ctx, req) => decorator(delegate, ctx, req)))

  /** Decorates HTTP services under the specified directory with the specified `decorator`. */
  def withDecoratorUnder(
      prefix: String,
      decorator: JFunction[_ >: HttpService, _ <: HttpService]): Self =
    atBuild(_.decoratorUnder(prefix, decorator))

  /** Configures the Armeria server using the specified
    * [[com.linecorp.armeria.server.ServerBuilder]].
    */
  def withArmeriaBuilder(customizer: ArmeriaBuilder => Unit): Self =
    atBuild { ab => customizer(ab); ab }

  /** Sets the idle timeout of a connection in milliseconds for keep-alive.
    *
    * @param idleTimeout
    *   the timeout. `scala.concurrent.duration.Duration.Zero` disables the timeout.
    */
  def withIdleTimeout(idleTimeout: FiniteDuration): Self =
    atBuild(_.idleTimeoutMillis(idleTimeout.toMillis))

  /** Sets the timeout of a request.
    *
    * @param requestTimeout
    *   the timeout. `scala.concurrent.duration.Duration.Zero` disables the timeout.
    */
  def withRequestTimeout(requestTimeout: FiniteDuration): Self =
    atBuild(_.requestTimeoutMillis(requestTimeout.toMillis))

  /** Adds an HTTP port that listens on all available network interfaces.
    *
    * @param port
    *   the HTTP port number.
    * @see
    *   [[com.linecorp.armeria.server.ServerBuilder#https(localAddress:java\.net\.InetSocketAddress):com\.linecorp\.armeria\.server\.ServerBuilder*]]
    */
  def withHttp(port: Int): Self = atBuild(_.http(port))

  /** Adds an HTTPS port that listens on all available network interfaces.
    *
    * @param port
    *   the HTTPS port number.
    * @see
    *   [[com.linecorp.armeria.server.ServerBuilder#https(localAddress:java\.net\.InetSocketAddress):com\.linecorp\.armeria\.server\.ServerBuilder*]]
    */
  def withHttps(port: Int): Self = atBuild(_.https(port))

  /** Sets the [[io.netty.channel.ChannelOption]] of the server socket bound by
    * [[com.linecorp.armeria.server.Server]]. Note that the previously added option will be
    * overridden if the same option is set again.
    *
    * @see
    *   [[https://armeria.dev/docs/advanced-production-checklist Production checklist]]
    */
  def withChannelOption[T](option: ChannelOption[T], value: T): Self =
    atBuild(_.channelOption(option, value))

  /** Sets the [[io.netty.channel.ChannelOption]] of sockets accepted by
    * [[com.linecorp.armeria.server.Server]]. Note that the previously added option will be
    * overridden if the same option is set again.
    *
    * @see
    *   [[https://armeria.dev/docs/advanced-production-checklist Production checklist]]
    */
  def withChildChannelOption[T](option: ChannelOption[T], value: T): Self =
    atBuild(_.childChannelOption(option, value))

  /** Configures SSL or TLS of this [[com.linecorp.armeria.server.Server]] from the specified
    * `keyCertChainFile`, `keyFile` and `keyPassword`.
    *
    * @see
    *   [[withTlsCustomizer]]
    */
  def withTls(keyCertChainFile: File, keyFile: File, keyPassword: Option[String]): Self =
    atBuild(_.tls(keyCertChainFile, keyFile, keyPassword.orNull))

  /** Configures SSL or TLS of this [[com.linecorp.armeria.server.Server]] with the specified
    * `keyCertChainInputStream`, `keyInputStream` and `keyPassword`.
    *
    * @see
    *   [[withTlsCustomizer]]
    */
  def withTls(
      keyCertChainInputStream: Resource[F, InputStream],
      keyInputStream: Resource[F, InputStream],
      keyPassword: Option[String]): Self =
    copy(addServices = (armeriaBuilder, dispatcher) =>
      addServices(armeriaBuilder, dispatcher).flatMap { ab =>
        keyCertChainInputStream
          .both(keyInputStream)
          .use { case (keyCertChain, key) =>
            F.delay {
              ab.tls(keyCertChain, key, keyPassword.orNull)
            }
          }
      })

  /** Configures SSL or TLS of this [[com.linecorp.armeria.server.Server]] with the specified
    * cleartext [[java.security.PrivateKey]] and [[java.security.cert.X509Certificate]] chain.
    *
    * @see
    *   [[withTlsCustomizer]]
    */
  def withTls(key: PrivateKey, keyCertChain: X509Certificate*): Self =
    atBuild(_.tls(key, keyCertChain: _*))

  /** Configures SSL or TLS of this [[com.linecorp.armeria.server.Server]] with the specified
    * [[javax.net.ssl.KeyManagerFactory]].
    *
    * @see
    *   [[withTlsCustomizer]]
    */
  def withTls(keyManagerFactory: KeyManagerFactory): Self =
    atBuild(_.tls(keyManagerFactory))

  /** Adds the specified `tlsCustomizer` which can arbitrarily configure the
    * [[io.netty.handler.ssl.SslContextBuilder]] that will be applied to the SSL session.
    */
  def withTlsCustomizer(tlsCustomizer: SslContextBuilder => Unit): Self =
    atBuild(_.tlsCustomizer(ctxBuilder => tlsCustomizer(ctxBuilder)))

  /** Sets the amount of time to wait after calling [[com.linecorp.armeria.server.Server#stop]] for
    * requests to go away before actually shutting down.
    *
    * @param quietPeriod
    *   the number of milliseconds to wait for active requests to go end before shutting down.
    *   `scala.concurrent.duration.Duration.Zero` means the server will stop right away without
    *   waiting.
    * @param timeout
    *   the amount of time to wait before shutting down the server regardless of active requests.
    *   This should be set to a time greater than `quietPeriod` to ensure the server shuts down even
    *   if there is a stuck request.
    */
  def withGracefulShutdownTimeout(quietPeriod: FiniteDuration, timeout: FiniteDuration): Self =
    atBuild(_.gracefulShutdownTimeoutMillis(quietPeriod.toMillis, timeout.toMillis))

  /** Sets the [[io.micrometer.core.instrument.MeterRegistry]] that collects various stats. */
  def withMeterRegistry(meterRegistry: MeterRegistry): Self =
    atBuild(_.meterRegistry(meterRegistry))

  private def shutdown(armeriaServer: BackendServer): F[Unit] =
    F.async_[Unit] { cb =>
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
      addServices: AddServices[F] = addServices,
      socketAddress: InetSocketAddress = socketAddress,
      serviceErrorHandler: ServiceErrorHandler[F] = serviceErrorHandler,
      banner: List[String] = banner
  ): Self =
    new ArmeriaServerBuilder(addServices, socketAddress, serviceErrorHandler, banner)

  private def atBuild(f: ArmeriaBuilder => ArmeriaBuilder): Self =
    copy(addServices = (armeriaBuilder, dispatcher) =>
      addServices(armeriaBuilder, dispatcher).map(f))
}

trait ArmeriaServer extends Server {
  def server: BackendServer
}

/** A builder that builds Armeria server for Http4s. */
object ArmeriaServerBuilder {
  type AddServices[F[_]] = (ArmeriaBuilder, Dispatcher[F]) => F[ArmeriaBuilder]

  /** Returns a newly created [[org.http4s.armeria.server.ArmeriaServerBuilder]]. */
  def apply[F[_]: Async]: ArmeriaServerBuilder[F] =
    new ArmeriaServerBuilder(
      (armeriaBuilder, _) => armeriaBuilder.pure,
      socketAddress = defaults.IPv4SocketAddress,
      serviceErrorHandler = DefaultServiceErrorHandler,
      banner = defaults.Banner)
}
