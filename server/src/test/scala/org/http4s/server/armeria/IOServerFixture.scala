/*
 * Copyright 2013-2020 http4s.org
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package org.http4s
package server
package armeria

import cats.effect.{ConcurrentEffect, ContextShift, IO}
import org.scalatest.Suite
import scala.concurrent.ExecutionContext

/** A [[ServerFixture]] that uses [[IO]] as the effect type */
trait IOServerFixture extends ServerFixture[IO] {
  this: Suite =>

  private val contextShift: ContextShift[IO] = IO.contextShift(ExecutionContext.global)
  protected implicit val F: ConcurrentEffect[IO] = IO.ioConcurrentEffect(contextShift)
}
