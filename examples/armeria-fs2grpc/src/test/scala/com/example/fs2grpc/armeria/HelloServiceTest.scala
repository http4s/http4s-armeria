/*
 * Copyright 2020-2021 http4s.org
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package com.example.fs2grpc.armeria

import cats.effect.IO
import cats.effect.std.Dispatcher
import com.linecorp.armeria.client.Clients
import com.linecorp.armeria.client.grpc.{GrpcClientOptions, GrpcClientStubFactory}
import example.armeria.grpc.hello.{HelloReply, HelloRequest, HelloServiceFs2Grpc, HelloServiceGrpc}
import io.grpc.{Channel, Metadata, ServiceDescriptor}
import fs2._
import munit.CatsEffectSuite

class HelloServiceTest extends CatsEffectSuite {
  private def setUp() = for {
    dispatcher <- Dispatcher[IO]
    armeriaServer <- Main.newServer(dispatcher, 0)
    httpPort = armeriaServer.server.activeLocalPort()
  } yield Clients
    .builder(s"gproto+http://127.0.0.1:$httpPort/grpc/")
    .option(GrpcClientOptions.GRPC_CLIENT_STUB_FACTORY.newValue(new GrpcClientStubFactory {

      override def findServiceDescriptor(clientType: Class[_]): ServiceDescriptor =
        HelloServiceGrpc.SERVICE

      override def newClientStub(clientType: Class[_], channel: Channel): AnyRef =
        HelloServiceFs2Grpc.stub[IO](dispatcher, channel)

    }))
    .build(classOf[HelloServiceFs2Grpc[IO, Metadata]])

  private val fixture = ResourceSuiteLocalFixture("fixture", setUp())

  override def munitFixtures = List(fixture)

  val message = "ScalaPB with Reactor"

  test("unary") {
    val client = fixture()
    val response = client.unary(HelloRequest(message), new Metadata())
    assertIO(response.map(_.message), s"Hello $message!")
  }

  test("serverStream") {
    val client = fixture()

    val response = client
      .serverStreaming(HelloRequest(message), new Metadata())
      .compile
      .toVector

    val expected = (1 to 5).map(i => HelloReply(s"Hello $message $i!")).toVector

    assertIO(response, expected)
  }

  test("clientStream") {
    val client = fixture()

    val response = client
      .clientStreaming(Stream.range(1, 6).map(i => HelloRequest(i.toString)), new Metadata())

    assertIO(response.map(_.message), "Hello 1, 2, 3, 4, 5!")
  }

  test("bidiStream") {
    val client = fixture()

    val responses = client
      .bidiStreaming(Stream(1, 2, 3).map(i => HelloRequest(i.toString)), new Metadata())
      .map(res => res.message)
      .compile
      .toVector

    val expected = (1 to 3).map(i => s"Hello $i!").toVector

    assertIO(responses, expected)
  }
}
