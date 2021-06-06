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
  def builder[F[_]: ConcurrentEffect: ContextShift: Timer]: ArmeriaServerBuilder[F] = {
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

  def resource[F[_]: ConcurrentEffect: ContextShift: Timer]: Resource[F, ArmeriaServer[F]] =
    builder[F].resource
}
