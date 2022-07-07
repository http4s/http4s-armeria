/*
 * Copyright 2020-2022 http4s.org
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package com.example.scalapb.armeria

import com.linecorp.armeria.client.Clients
import example.armeria.grpc.hello.ReactorHelloServiceGrpc.ReactorHelloServiceStub
import example.armeria.grpc.hello.{HelloReply, HelloRequest}
import munit.CatsEffectSuite
import reactor.core.scala.publisher.SFlux

import scala.concurrent.duration.DurationInt

class HelloServiceTest extends CatsEffectSuite {
  private def setUp() =
    Main.newServer(0).map { armeriaServer =>
      val httpPort = armeriaServer.server.activeLocalPort()

      Clients
        .builder(s"gproto+http://127.0.0.1:$httpPort/grpc/")
        .build(classOf[ReactorHelloServiceStub])
    }

  private val fixture = ResourceSuiteLocalFixture("fixture", setUp())

  override def munitFixtures = List(fixture)

  val message = "ScalaPB with Reactor"

  test("unary") {
    val client = fixture()
    val response = client.unary(HelloRequest(message)).block()
    assertEquals(response.message, s"Hello $message!")
  }

  test("serverStream") {
    val client = fixture()
    val response = client.serverStreaming(HelloRequest(message)).collectSeq().block()
    val expected = (1 to 5).map(i => HelloReply(s"Hello $message $i!"))
    assertEquals(response, expected)
  }

  test("clientStream") {
    val client = fixture()
    val response = client
      .clientStreaming(
        SFlux
          .range(1, 5)
          .delayElements(100.millis)
          .map(i => HelloRequest(i.toString))
      )
      .block()
    assertEquals(response.message, "Hello 1, 2, 3, 4, 5!")
  }

  test("bidiStream") {
    val client = fixture()
    val responses = client
      .bidiStreaming(SFlux(1, 2, 3).map(i => HelloRequest(i.toString)))
      .map(res => res.message)
      .collectSeq()
      .block()
    val expected = (1 to 3).map(i => s"Hello $i!")
    assertEquals(responses, expected)
  }
}
