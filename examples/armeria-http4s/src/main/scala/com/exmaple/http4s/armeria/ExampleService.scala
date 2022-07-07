/*
 * Copyright 2020-2022 http4s.org
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package com.exmaple.http4s
package armeria

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
