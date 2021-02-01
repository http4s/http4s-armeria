/*
 * Copyright 2020-2021 http4s.org
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package com.example.scalapb.armeria

import cats.effect.concurrent.Deferred
import cats.effect.{ContextShift, IO}
import com.linecorp.armeria.client.Clients
import example.armeria.grpc.hello.ReactorHelloServiceGrpc.ReactorHelloServiceStub
import example.armeria.grpc.hello.{HelloReply, HelloRequest, HelloServiceGrpc}
import io.grpc.stub.StreamObserver
import org.scalatest.BeforeAndAfterAll
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.must.Matchers
import reactor.core.scala.publisher.SFlux
import scala.collection.mutable
import scala.concurrent.duration.{Duration, DurationInt}
import scala.concurrent.{Await, ExecutionContext}

class HelloServiceTest extends AnyFunSuite with BeforeAndAfterAll with Matchers {

  implicit val ec: ContextShift[IO] = IO.contextShift(ExecutionContext.global)

  private var releaseToken: IO[Unit] = _
  private var httpPort: Int = _
  private var client: ReactorHelloServiceStub = _

  override protected def beforeAll(): Unit = {
    val (armeriaServer, token) = Main.newServer(0).allocated.unsafeRunSync()
    httpPort = armeriaServer.server.activeLocalPort()
    releaseToken = token

    client = Clients
      .builder(s"gproto+http://127.0.0.1:$httpPort/grpc/")
      .build(classOf[ReactorHelloServiceStub])
  }

  override protected def afterAll(): Unit = releaseToken.unsafeRunSync()

  val message = "ScalaPB with Reactor"
  test("unary") {
    val response = client.unary(HelloRequest(message)).block()
    response.message must be(s"Hello $message!")
  }

  test("serverStream") {
    val response = client.serverStreaming(HelloRequest(message)).collectSeq().block()
    val expected = (1 to 5).map(i => HelloRequest(s"Hello $message $i!"))
    response must be(expected)
  }

  test("clientStream") {
    val response = client
      .clientStreaming(
        SFlux
          .range(1, 5)
          .delayElements(100.millis)
          .map(i => HelloRequest(i.toString))
      )
      .block()
    response.message must be("Hello 1, 2, 3, 4, 5!")
  }

  test("bidiStream") {
    val responses = client
      .bidiStreaming(SFlux(1, 2, 3).map(i => HelloRequest(i.toString)))
      .map(res => res.message)
      .collectSeq()
      .block()
    val expected = (1 to 3).map(i => s"Hello $i!")
    responses must be(expected)
  }
}
