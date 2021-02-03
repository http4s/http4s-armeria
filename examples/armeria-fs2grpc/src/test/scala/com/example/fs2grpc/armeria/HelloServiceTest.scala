/*
 * Copyright 2020-2021 http4s.org
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package com.example.fs2grpc.armeria

import cats.effect.{ContextShift, IO}
import com.linecorp.armeria.client.Clients
import com.linecorp.armeria.client.grpc.{GrpcClientOptions, GrpcClientStubFactory}
import example.armeria.grpc.hello.{HelloReply, HelloRequest, HelloServiceFs2Grpc, HelloServiceGrpc}
import io.grpc.{Channel, Metadata, ServiceDescriptor}
import fs2._
import org.scalatest.BeforeAndAfterAll
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.must.Matchers
import scala.concurrent.ExecutionContext

class HelloServiceTest extends AnyFunSuite with BeforeAndAfterAll with Matchers {

  implicit val ec: ContextShift[IO] = IO.contextShift(ExecutionContext.global)

  private var releaseToken: IO[Unit] = _
  private var httpPort: Int = _
  private var client: HelloServiceFs2Grpc[IO, Metadata] = _

  override protected def beforeAll(): Unit = {
    val (armeriaServer, token) = Main.newServer(0).allocated.unsafeRunSync()
    httpPort = armeriaServer.server.activeLocalPort()
    releaseToken = token

    client = Clients
      .builder(s"gproto+http://127.0.0.1:$httpPort/grpc/")
      .option(GrpcClientOptions.GRPC_CLIENT_STUB_FACTORY.newValue(new GrpcClientStubFactory {

        override def findServiceDescriptor(clientType: Class[_]): ServiceDescriptor =
          HelloServiceGrpc.SERVICE

        override def newClientStub(clientType: Class[_], channel: Channel): AnyRef =
          HelloServiceFs2Grpc.stub[IO](channel)

      }))
      .build(classOf[HelloServiceFs2Grpc[IO, Metadata]])
  }

  override protected def afterAll(): Unit = releaseToken.unsafeRunSync()

  val message = "ScalaPB with Reactor"
  test("unary") {
    val response = client.unary(HelloRequest(message), new Metadata()).unsafeRunSync()
    response.message must be(s"Hello $message!")
  }

  test("serverStream") {
    val response = client
      .serverStreaming(HelloRequest(message), new Metadata())
      .compile
      .toVector
      .unsafeRunSync()
    val expected = (1 to 5).map(i => HelloReply(s"Hello $message $i!"))
    response must be(expected)
  }

  test("clientStream") {
    val response = client
      .clientStreaming(Stream.range(1, 6).map(i => HelloRequest(i.toString)), new Metadata())
      .unsafeRunSync()
    response.message must be("Hello 1, 2, 3, 4, 5!")
  }

  test("bidiStream") {
    val responses = client
      .bidiStreaming(Stream(1, 2, 3).map(i => HelloRequest(i.toString)), new Metadata())
      .map(res => res.message)
      .compile
      .toVector
      .unsafeRunSync()
    val expected = (1 to 3).map(i => s"Hello $i!")
    responses must be(expected)
  }
}
