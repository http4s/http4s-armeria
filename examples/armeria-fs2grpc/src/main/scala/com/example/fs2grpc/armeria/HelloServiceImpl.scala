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

package com.example.fs2grpc.armeria

import cats.effect.IO
import example.armeria.grpc.hello.{HelloReply, HelloRequest, HelloServiceFs2Grpc}
import io.grpc.Metadata
import fs2._

class HelloServiceImpl extends HelloServiceFs2Grpc[IO, Metadata] {

  override def unary(request: HelloRequest, ctx: Metadata): IO[HelloReply] =
    IO(HelloReply(s"Hello ${request.name}!"))

  override def serverStreaming(request: HelloRequest, ctx: Metadata): Stream[IO, HelloReply] =
    Stream
      .range(1, 6)
      .map(i => s"Hello ${request.name} $i!")
      .map(HelloReply(_))

  override def clientStreaming(request: Stream[IO, HelloRequest], ctx: Metadata): IO[HelloReply] =
    request
      .map(_.name)
      .compile
      .toVector
      .map(_.mkString(", "))
      .map(all => HelloReply(s"Hello $all!"))

  override def bidiStreaming(
      request: Stream[IO, HelloRequest],
      ctx: Metadata): Stream[IO, HelloReply] =
    request.map(req => HelloReply(s"Hello ${req.name}!"))
}
