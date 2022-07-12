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

package org.http4s.armeria.server

import cats.effect.{IO, Resource}
import com.linecorp.armeria.common.SessionProtocol
import com.linecorp.armeria.server.Server
import java.net.URI

import munit.{CatsEffectFunFixtures, CatsEffectSuite}

import scala.concurrent.duration._
import scala.util.Try

/** A fixture that starts and stops an Armeria server automatically before and after executing a
  * test or all tests .
  */
trait ServerFixture extends CatsEffectFunFixtures {
  this: CatsEffectSuite =>

  private var armeriaServerWrapper: ArmeriaServer = _
  private var server: Server = _
  private var releaseToken: IO[Unit] = _

  /** Configures the [[Server]] with the given [[ArmeriaServerBuilder]]. */
  protected def configureServer(customizer: ArmeriaServerBuilder[IO]): ArmeriaServerBuilder[IO]

  protected def httpPort: Try[Int] = Try(server.activeLocalPort(SessionProtocol.HTTP))
  protected def httpUri: Try[URI] = httpPort.map(port => URI.create(s"http://127.0.0.1:$port"))

  protected def httpsPort: Try[Int] = Try(server.activeLocalPort(SessionProtocol.HTTPS))
  protected def httpsUri: Try[URI] = httpsPort.map(port => URI.create(s"https://127.0.0.1:$port"))

  val armeriaServerFixture: Fixture[Unit] = ResourceSuiteLocalFixture(
    "armeria-server-fixture",
    Resource.make(IO(setUp()))(_ => IO(tearDown()))
  )

  private def setUp(): Unit = {
    val serverBuilder = ArmeriaServerBuilder[IO].withGracefulShutdownTimeout(0.seconds, 0.seconds)
    val configured = configureServer(serverBuilder)
    val allocated = configured.resource.allocated.unsafeRunSync()
    armeriaServerWrapper = allocated._1
    server = armeriaServerWrapper.server
    releaseToken = allocated._2
  }

  private def tearDown(): Unit = releaseToken.unsafeRunSync()
}
