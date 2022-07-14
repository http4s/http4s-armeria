/*
 * Copyright 2020-2022 http4s.org
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package com.exmaple.http4s
package armeria

import cats.effect._
import com.linecorp.armeria.common.metric.{MeterIdPrefixFunction, PrometheusMeterRegistries}
import com.linecorp.armeria.server.metric.{MetricCollectingService, PrometheusExpositionService}
import org.http4s.armeria.server.{ArmeriaServer, ArmeriaServerBuilder}

object ArmeriaExample extends IOApp {
  override def run(args: List[String]): IO[ExitCode] =
    ArmeriaExampleApp.resource[IO].use(_ => IO.never).as(ExitCode.Success)
}

object ArmeriaExampleApp {
  def builder[F[_]: ConcurrentEffect: Timer]: ArmeriaServerBuilder[F] = {
    val registry = PrometheusMeterRegistries.newRegistry()
    val prometheusRegistry = registry.getPrometheusRegistry
    ArmeriaServerBuilder[F]
      .bindHttp(8080)
      .withMeterRegistry(registry)
      .withHttpRoutes("/http4s", ExampleService[F].routes())
      .withHttpService("/metrics", PrometheusExpositionService.of(prometheusRegistry))
      .withDecorator(
        MetricCollectingService.newDecorator(MeterIdPrefixFunction.ofDefault("server")))
      .withDecoratorUnder("/black-knight", new NoneShallPass(_))
  }

  def resource[F[_]: ConcurrentEffect: Timer]: Resource[F, ArmeriaServer] =
    builder[F].resource
}
