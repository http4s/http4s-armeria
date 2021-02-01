# http4s-armeria

[![Maven Central](https://maven-badges.herokuapp.com/maven-central/org.http4s/http4s-armeria-server_2.13/badge.svg)](https://search.maven.org/search?q=http4s-armeria)
[![Build Status](https://github.com/http4s/http4s-armeria/workflows/Build%20Pull%20Requests/badge.svg?branch=main)](https://github.com/http4s/http4s-armeria/actions?query=workflow%3A"Build+c+Requests")


[Http4s] server and client on [Armeria]

## Highlights

- You can run Http4s services on top of Armeria's asynchronous and reactive server.
- You can maximize your service and client with Armeria's [awesome features](https://armeria.dev/docs#features)
  such as:
    - gRPC server and client
    - Circuit Breaker
    - Automatic retry
    - Dynamic service discovery
    - Distributed tracing
    - [and](https://armeria.dev/docs/server-docservice) [so](https://armeria.dev/docs/server-thrift) [on](https://armeria.dev/docs/advanced-metrics)

## Installation

Add the following dependencies to `build.sbt`
```sbt
// For server
libraryDependencies += "org.http4s" %% "http4s-armeria-server" % "<lastest-version>"
// For client
libraryDependencies += "org.http4s" %% "http4s-armeria-client" % "<lastest-version>"
```

## Quick start

### Run your service with Armeria server

```scala
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
      // Sets your own meter registry
      .withMeterRegistry(registry)
      // Binds HttpRoutes to Armeria server
      .withHttpRoutes("/http4s", ExampleService[F].routes())
      // Adds PrometheusExpositionService provided by Armeria for exposing Prometheus metrics
      .withHttpService("/metrics", PrometheusExpositionService.of(prometheusRegistry))
      // Decorates your services with MetricCollectingService for collecting metrics
      .withDecorator(
        MetricCollectingService.newDecorator(MeterIdPrefixFunction.ofDefault("server")))
  }

  def resource[F[_]: ConcurrentEffect: ContextShift: Timer]: Resource[F, ArmeriaServer[F]] =
    builder[F].resource
}
```

### Call your service with Armeria client

```scala
val client: Client[IO] = 
  ArmeriaClientBuilder
    .unsafe[IO](s"http://127.0.0.1:${server.activeLocalPort()}")
    // Automically retry on unprocessed requests
    .withDecorator(RetryingClient.newDecorator(RetryRule.onUnprocessed()))
    // Open circuit on 5xx server error status
    .withDecorator(CircuitBreakerClient.newDecorator(CircuitBreaker.ofDefaultName(),
                                                     CircuitBreakerRule.onServerErrorStatus()))
    // Log requests and responses
    .withDecorator(LoggingClient.newDecorator())
    .withResponseTimeout(10.seconds)
    .build()
    
val response = client.expect[String]("Armeria").unsafeRunSync()
```

Visit [examples](./examples) to find a fully working example.

[http4s]: https://http4s.org/
[armeria]: https://armeria.dev/
