/*
 * Copyright 2020-2021 http4s.org
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package com.example.scalapb.armeria

import example.armeria.grpc.hello.{HelloReply, HelloRequest}
import example.armeria.grpc.hello.ReactorHelloServiceGrpc.ReactorHelloService
import reactor.core.scala.publisher.{SFlux, SMono}

class HelloServiceImpl extends ReactorHelloService {
  override def unary(request: HelloRequest): SMono[HelloReply] =
    SMono.just(HelloReply(s"Hello ${request.name}!"))

  override def serverStreaming(request: HelloRequest): SFlux[HelloReply] =
    SFlux
      .range(1, 5)
      .map(i => s"Hello ${request.name} $i!")
      .map(HelloReply(_))

  override def clientStreaming(requests: SFlux[HelloRequest]): SMono[HelloReply] =
    requests.map(_.name).collectSeq().map(_.mkString(", ")).map(all => HelloReply(s"Hello $all!"))

  override def bidiStreaming(requests: SFlux[HelloRequest]): SFlux[HelloReply] =
    requests.map(req => HelloReply(s"Hello ${req.name}!"))
}
