# http4s-armeria

[![Maven Central](https://img.shields.io/maven-central/v/org.http4s/http4s-armeria-server_2.13?versionPrefix=0.)](https://img.shields.io/maven-central/v/org.http4s/http4s-armeria-server_2.13?versionPrefix=0.)
![Build Status](https://github.com/http4s/http4s-armeria/actions/workflows/ci.yml/badge.svg?branch=main)


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

## Current status

Two series are currently under active development: the `0.x` and `1.0-x` release milestone series.
The first depends on the `http4s-core`'s `0.23` series and belongs to the [main branch].
The latter is for the cutting-edge `http4s-core`'s `1.0-x` release milestone series and belongs to the [series/1.x branch].

## Installation

Add the following dependencies to `build.sbt`
```sbt
// For server
libraryDependencies += "org.http4s" %% "http4s-armeria-server" % "<lastest-version>"
// For client
libraryDependencies += "org.http4s" %% "http4s-armeria-client" % "<lastest-version>"
```

## Quick start

### http4s integration

#### Run your http4s service with [Armeria server](https://armeria.dev/docs/server-basics)

You can bind your http4s service using `ArmeriaServerBuilder[F].withHttpRoutes()`.

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

#### Call your service with http4s-armeria client

You can create http4s client using `ArmeriaClientBuilder`.

```scala
import com.linecorp.armeria.client.circuitbreaker._
import com.linecopr.armeria.client.logging._
import com.linecopr.armeria.client.retry._
import org.http4s.armeria.client.ArmeriaClientBuilder 

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

### fs2-grpc integration

#### Run your [fs2-grpc](https://github.com/fiadliel/fs2-grpc) service with Armeria [gRPC server](https://armeria.dev/docs/server-grpc)

Add the following dependencies to `build.sbt`.

```sbt
libraryDependencies += Seq(
  "com.linecorp.armeria" % "armeria-grpc" % "1.5.0",
  "com.linecorp.armeria" %% "armeria-scalapb" % "1.5.0")
```

Add your fs2-grpc service to `GrpcService`.

```scala
import com.linecorp.armeria.server.grpc.GrpcService
import com.linecorp.armeria.common.scalapb.ScalaPbJsonMarshaller

// Build gRPC service
val grpcService = GrpcService
  .builder()
  .addService(HelloServiceFs2Grpc.bindService(new HelloServiceImpl))
  // Register `ScalaPbJsonMarshaller` to support gRPC JSON format
  .jsonMarshallerFactory(_ => ScalaPbJsonMarshaller())
  .enableUnframedRequests(true)
  .build()
```

You can run http4s service and gRPC service together with sharing a single HTTP port.

```scala
ArmeriaServerBuilder[IO]
  .bindHttp(httpPort)
  .withHttpServiceUnder("/grpc", grpcService)
  .withHttpRoutes("/rest", ExampleService[IO].routes())
  .resource
```

#### Call your gRPC service using fs2-grpc with Armeria [gRPC client](https://armeria.dev/docs/client-grpc)

```scala
import com.linecorp.armeria.client.Clients
import com.linecorp.armeria.client.grpc.{GrpcClientOptions, GrpcClientStubFactory}

val client: HelloServiceFs2Grpc[IO, Metadata] =
 Clients
   .builder(s"gproto+http://127.0.0.1:$httpPort/grpc/")
   // Add a circuit breaker for your gRPC client
   .decorator(CircuitBreakerClient.newDecorator(CircuitBreaker.ofDefaultName(),
                                                CircuitBreakerRule.onServerErrorStatus()))
   .option(GrpcClientOptions.GRPC_CLIENT_STUB_FACTORY.newValue(new GrpcClientStubFactory {
     // Specify `ServiceDescriptor` of your generated gRPC stub
     override def findServiceDescriptor(clientType: Class[_]): ServiceDescriptor =
       HelloServiceGrpc.SERVICE

     // Returns a newly created gRPC client stub from the given `Channel`
     override def newClientStub(clientType: Class[_], channel: Channel): AnyRef =
       HelloServiceFs2Grpc.stub[IO](channel)

   }))
   .build(classOf[HelloServiceFs2Grpc[IO, Metadata]])
```

Visit [examples](./examples) to find a fully working example.

[http4s]: https://http4s.org/
[armeria]: https://armeria.dev/
[main branch]: https://github.com/http4s/http4s-armeria/tree/main
[series/1.x branch]: https://github.com/http4s/http4s-armeria/tree/series/1.x
