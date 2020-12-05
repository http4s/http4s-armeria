package org.http4s
package armeria
package client

import cats.effect.{ConcurrentEffect, Resource}
import com.linecorp.armeria.client.{
  ClientFactory,
  ClientOptionValue,
  ClientOptions,
  ClientRequestContext,
  HttpClient,
  WebClient,
  WebClientBuilder
}
import com.linecorp.armeria.common.{HttpRequest, HttpResponse}
import java.net.URI
import java.util.function.{Function => JFunction}
import org.http4s.client.Client
import org.http4s.internal.BackendBuilder
import scala.concurrent.duration.FiniteDuration

sealed class ArmeriaClientBuilder[F[_]] private (clientBuilder: WebClientBuilder)(implicit
    protected val
    F: ConcurrentEffect[F])
    extends BackendBuilder[F, Client[F]] {

  type DecoratingFunction = (HttpClient, ClientRequestContext, HttpRequest) => HttpResponse

  /** Configures the Armeria client using the specified [[com.linecorp.armeria.client.WebClientBuilder]]. */
  def withArmeriaBuilder(customizer: WebClientBuilder => Unit): ArmeriaClientBuilder[F] = {
    customizer(clientBuilder)
    this
  }

  /** Sets the [[com.linecorp.armeria.client.ClientFactory]] used for creating a client.
    * The default is [[com.linecorp.armeria.client.ClientFactory.ofDefault]].
    */
  def withClientFactory(clientFactory: ClientFactory): ArmeriaClientBuilder[F] = {
    clientBuilder.factory(clientFactory)
    this
  }

  /** Sets the timeout of a response.
    *
    * @param responseTimeout the timeout. [[scala.concurrent.duration.Duration.Zero]] disables the timeout.
    */
  def withResponseTimeout(responseTimeout: FiniteDuration): ArmeriaClientBuilder[F] = {
    clientBuilder.responseTimeoutMillis(responseTimeout.toMillis)
    this
  }

  /** Sets the timeout of a socket write attempt.
    *
    * @param writeTimeout the timeout. [[scala.concurrent.duration.Duration.Zero]] disables the timeout.
    */
  def withWriteTimeout(writeTimeout: FiniteDuration): ArmeriaClientBuilder[F] = {
    clientBuilder.writeTimeoutMillis(writeTimeout.toMillis)
    this
  }

  /** Sets the maximum allowed length of a server response in bytes.
    *
    * @param maxResponseLength the maximum length in bytes. `0` disables the limit.
    */
  def withMaxResponseLength(maxResponseLength: Long): ArmeriaClientBuilder[F] = {
    clientBuilder.maxResponseLength(maxResponseLength)
    this
  }

  /** Adds the specified [[com.linecorp.armeria.client.ClientOptionValue]]. */
  def withClientOption[A](option: ClientOptionValue[A]): ArmeriaClientBuilder[F] = {
    clientBuilder.option(option)
    this
  }

  /** Adds the specified [[com.linecorp.armeria.client.ClientOptions]]. */
  def withClientOptions[A](options: ClientOptions): ArmeriaClientBuilder[F] = {
    clientBuilder.options(options)
    this
  }

  /** Adds the specified [[com.linecorp.armeria.client.ClientOptionValue]]s. */
  def withClientOptions[A](options: ClientOptionValue[_]*): ArmeriaClientBuilder[F] = {
    options.foreach(withClientOption(_))
    this
  }

  /** Adds the specified `decorator`.
    *
    * @param decorator the [[DecoratingFunction]] that transforms an
    *                  [[com.linecorp.armeria.client.HttpClient]] to another.
    */
  def withDecorator(decorator: DecoratingFunction): ArmeriaClientBuilder[F] = {
    clientBuilder.decorator(decorator(_, _, _))
    this
  }

  /** Adds the specified `decorator`.
    *
    * @param decorator the [[java.util.function.Function]] that transforms an
    *                  [[com.linecorp.armeria.client.HttpClient]] to another.
    */
  def withDecorator(
      decorator: JFunction[_ >: HttpClient, _ <: HttpClient]): ArmeriaClientBuilder[F] = {
    clientBuilder.decorator(decorator)
    this
  }

  /** Returns a newly-created http4s [[org.http4s.client.Client]] based on
    * Armeria [[com.linecorp.armeria.client.WebClient]].
    */
  def build: Client[F] = ArmeriaClient(clientBuilder.build())

  override def resource: Resource[F, Client[F]] =
    Resource.pure(build)
}

/** A builder class that builds http4s [[org.http4s.client.Client]] based on
  * Armeria [[com.linecorp.armeria.client.WebClient]].
  */
object ArmeriaClientBuilder {

  /** Returns a new [[ArmeriaClientBuilder]]. */
  def apply[F[_]](clientBuilder: WebClientBuilder = WebClient.builder())(implicit
      F: ConcurrentEffect[F]): ArmeriaClientBuilder[F] =
    new ArmeriaClientBuilder(clientBuilder)

  /** Returns a new [[ArmeriaClientBuilder]] created with the specified base [[java.net.URI]].
    *
    * @return `Left(IllegalArgumentException)` if the `uri` is not valid or its scheme is not one of the values
    *         values in [[com.linecorp.armeria.common.SessionProtocol.httpValues]] or
    *         [[com.linecorp.armeria.common.SessionProtocol.httpsValues]], else
    *         `Right(ArmeriaClientBuilder)`.
    */
  def apply[F[_]](uri: String)(implicit
      F: ConcurrentEffect[F]): Either[IllegalArgumentException, ArmeriaClientBuilder[F]] =
    try Right(unsafe(uri))
    catch {
      case ex: IllegalArgumentException => Left(ex)
    }

  /** Returns a new [[ArmeriaClientBuilder]] created with the specified base [[java.net.URI]].
    *
    * @throws scala.IllegalArgumentException if the `uri` is not valid or its scheme is not one of the values
    *                                  in [[com.linecorp.armeria.common.SessionProtocol.httpValues]] or
    *                                  [[com.linecorp.armeria.common.SessionProtocol.httpsValues]].
    */
  def unsafe[F[_]](uri: String)(implicit F: ConcurrentEffect[F]): ArmeriaClientBuilder[F] =
    apply(WebClient.builder(uri))

  /** Returns a new [[ArmeriaClientBuilder]] created with the specified base [[java.net.URI]].
    *
    * @return `Left(IllegalArgumentException)` if the [[java.net.URI]] is not valid or its scheme is not one of the
    *          values in [[com.linecorp.armeria.common.SessionProtocol.httpValues]] or
    *          [[com.linecorp.armeria.common.SessionProtocol.httpsValues]], else
    *          `Right(ArmeriaClientBuilder)`.
    */
  def apply[F[_]](uri: URI)(implicit
      F: ConcurrentEffect[F]): Either[IllegalArgumentException, ArmeriaClientBuilder[F]] =
    try Right(apply(WebClient.builder(uri)))
    catch {
      case ex: IllegalArgumentException => Left(ex)
    }

  /** Returns a new [[ArmeriaClientBuilder]] created with the specified base [[java.net.URI]].
    *
    * @throws scala.IllegalArgumentException if the [[java.net.URI]] is not valid or its scheme is not one of the values
    *                                  values in [[com.linecorp.armeria.common.SessionProtocol.httpValues]] or
    *                                  [[com.linecorp.armeria.common.SessionProtocol.httpsValues]].
    */
  def unsafe[F[_]](uri: URI)(implicit F: ConcurrentEffect[F]): ArmeriaClientBuilder[F] =
    apply(WebClient.builder(uri))
}
