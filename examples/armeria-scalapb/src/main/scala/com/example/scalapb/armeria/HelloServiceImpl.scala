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

import example.armeria.grpc.hello.ReactorHelloServiceGrpc.ReactorHelloService
import example.armeria.grpc.hello.{HelloReply, HelloRequest}
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
