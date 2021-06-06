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

import cats.effect.{IO, Resource}
import io.netty.handler.ssl.util.SelfSignedCertificate
import java.io.FileInputStream
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.must.Matchers
import com.linecorp.armeria.client.{ClientFactory, WebClient}
import com.linecorp.armeria.common.HttpStatus
import org.http4s.HttpRoutes
import org.http4s.dsl.io._

class TlsServerSpec extends AnyFunSuite with IOServerFixture with Matchers {

  val routes = HttpRoutes.of[IO] {
    case GET -> Root / "tls" =>
      Ok()
  }

  override protected def configureServer(
      customizer: ArmeriaServerBuilder[IO]): ArmeriaServerBuilder[IO] = {
    val certificate = new SelfSignedCertificate
    val certR = Resource.fromAutoCloseable(IO(new FileInputStream(certificate.certificate)))
    val keyR = Resource.fromAutoCloseable(IO(new FileInputStream(certificate.privateKey())))
    customizer
      .withHttps(0)
      .withHttpRoutes("/", routes)
      .withTls(certR, keyR, None)
      .unsafeRunSync()
  }

  test("https requests") {
    val client = WebClient
      .builder(httpsUri.get)
      .factory(ClientFactory.insecure())
      .build()
    client.get("/tls").aggregate().join().status() must be(HttpStatus.OK)
  }
}
