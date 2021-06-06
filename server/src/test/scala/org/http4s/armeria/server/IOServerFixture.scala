/*
 * Copyright 2020 Ikhun
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

package org.http4s.armeria.server

import cats.effect.{ConcurrentEffect, ContextShift, IO}
import org.scalatest.Suite
import scala.concurrent.ExecutionContext

/** A [[ServerFixture]] that uses [[IO]] as the effect type */
trait IOServerFixture extends ServerFixture[IO] {
  this: Suite =>

  scala.concurrent.duration.Duration
  private val contextShift: ContextShift[IO] = IO.contextShift(ExecutionContext.global)
  protected implicit val F: ConcurrentEffect[IO] = IO.ioConcurrentEffect(contextShift)
}
