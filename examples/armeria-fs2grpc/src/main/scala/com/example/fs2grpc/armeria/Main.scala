/*
 * Copyright 2020-2022 http4s.org
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package com.example.fs2grpc.armeria

import cats.effect.std.Dispatcher
import cats.effect.{ExitCode, IO, IOApp, Resource}
import com.linecorp.armeria.common.scalapb.ScalaPbJsonMarshaller
import com.linecorp.armeria.server.docs.{DocService, DocServiceFilter}
import com.linecorp.armeria.server.grpc.GrpcService
import com.linecorp.armeria.server.logging.LoggingService
import example.armeria.grpc.hello.{HelloRequest, HelloServiceFs2Grpc, HelloServiceGrpc}
import io.grpc.reflection.v1alpha.ServerReflectionGrpc
import org.http4s.armeria.server.{ArmeriaServer, ArmeriaServerBuilder}
import org.slf4j.LoggerFactory

import scala.concurrent.duration.Duration

object Main extends IOApp {

  val logger = LoggerFactory.getLogger(getClass)

  override def run(args: List[String]): IO[ExitCode] =
    Dispatcher[IO]
      .flatMap(dispatcher => newServer(dispatcher, 8080))
      .use { armeria =>
        logger.info(
          s"Server has been started. Serving DocService at http://127.0.0.1:${armeria.server.activeLocalPort()}/docs"
        )
        IO.never
      }
      .as(ExitCode.Success)

  def newServer(dispatcher: Dispatcher[IO], httpPort: Int): Resource[IO, ArmeriaServer] = {
    // Build gRPC service
    val grpcService = GrpcService
      .builder()
      .addService(HelloServiceFs2Grpc.bindService(dispatcher, new HelloServiceImpl))
      // Register ScalaPbJsonMarshaller to support gRPC JSON format
      .jsonMarshallerFactory(_ => ScalaPbJsonMarshaller())
      .enableUnframedRequests(true)
      .build()

    val exampleRequest = HelloRequest("Armeria")
    val serviceName = HelloServiceGrpc.SERVICE.getName

    ArmeriaServerBuilder[IO]
      .bindHttp(httpPort)
      .withIdleTimeout(Duration.Zero)
      .withRequestTimeout(Duration.Zero)
      .withHttpServiceUnder("/grpc", grpcService)
      .withHttpRoutes("/rest", ExampleService[IO].routes())
      .withDecorator(LoggingService.newDecorator())
      // Add DocService for browsing the list of gRPC services and
      // invoking a service operation from a web form.
      // See https://armeria.dev/docs/server-docservice for more information.
      .withHttpServiceUnder(
        "/docs",
        DocService
          .builder()
          .exampleRequests(serviceName, "Hello", exampleRequest)
          .exampleRequests(serviceName, "LazyHello", exampleRequest)
          .exampleRequests(serviceName, "BlockingHello", exampleRequest)
          .exclude(DocServiceFilter.ofServiceName(ServerReflectionGrpc.SERVICE_NAME))
          .build()
      )
      .withGracefulShutdownTimeout(Duration.Zero, Duration.Zero)
      .resource
  }
}
