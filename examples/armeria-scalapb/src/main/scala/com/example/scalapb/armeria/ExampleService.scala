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

import cats.effect._
import com.linecorp.armeria.common.util.TimeoutMode
import com.linecorp.armeria.server.ServiceRequestContext
import fs2._
import org.http4s._
import org.http4s.armeria.server.ServiceRequestContexts
import org.http4s.dsl.Http4sDsl
import scala.concurrent.duration._

class ExampleService[F[_]](implicit F: Async[F]) extends Http4sDsl[F] {

  def routes(): HttpRoutes[F] =
    HttpRoutes.of[F] {
      case GET -> Root / "thread" =>
        Ok(Thread.currentThread.getName)

      case GET -> Root / "context" / "threadlocal" =>
        val ctx = ServiceRequestContext.current
        Ok(s"context id: ${ctx.id().text()}")

      case req @ GET -> Root / "context" / "attribute" =>
        req.attributes
          .lookup(ServiceRequestContexts.Key)
          .fold(Ok("context id: unknown")) { ctx =>
            Ok(s"context id: ${ctx.id().text()}")
          }

      case GET -> Root / "streaming" =>
        val ctx = ServiceRequestContext.current
        ctx.setRequestTimeoutMillis(2.seconds.toMillis)
        val stream =
          Stream
            .fixedDelay(1.second)
            .evalMap(_ =>
              F.delay {
                ctx.setRequestTimeoutMillis(TimeoutMode.EXTEND, 2.seconds.toMillis)
                "Hello!\n"
              })
            .take(10)
        Ok(stream)
    }
}

object ExampleService {
  def apply[F[_]: Async] = new ExampleService[F]
}
