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

package com.example.scalapb.armeria

import cats.effect.{ExitCode, IO, IOApp, Resource}
import com.linecorp.armeria.common.scalapb.ScalaPbJsonMarshaller
import com.linecorp.armeria.server.docs.{DocService, DocServiceFilter}
import com.linecorp.armeria.server.grpc.GrpcService
import com.linecorp.armeria.server.logging.LoggingService
import example.armeria.grpc.hello.ReactorHelloServiceGrpc.ReactorHelloService
import example.armeria.grpc.hello.{HelloRequest, HelloServiceGrpc}
import io.grpc.reflection.v1alpha.ServerReflectionGrpc
import org.http4s.armeria.server.{ArmeriaServer, ArmeriaServerBuilder}
import org.slf4j.LoggerFactory
import scala.concurrent.duration.Duration

object Main extends IOApp {

  val logger = LoggerFactory.getLogger(getClass)

  override def run(args: List[String]): IO[ExitCode] =
    newServer(8080)
      .use { armeria =>
        logger.info(
          s"Server has been started. Serving DocService at http://127.0.0.1:${armeria.server.activeLocalPort
            () }/docs"
        )
        IO.never
      }
      .as(ExitCode.Success)

  def newServer(httpPort: Int): Resource[IO, ArmeriaServer] = {
    // Build gRPC service
    val grpcService = GrpcService
      .builder()
      // TODO(ikhoon): Support fs2-grpc with Armeria gRPC server and client.
      .addService(ReactorHelloService.bindService(new HelloServiceImpl))
      // Register ScalaPbJsonMarshaller to support gRPC JSON format
      .jsonMarshallerFactory(_ => ScalaPbJsonMarshaller())
      //      .enableUnframedRequests(true)
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
