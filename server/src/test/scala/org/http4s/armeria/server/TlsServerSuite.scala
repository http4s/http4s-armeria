package org.http4s.armeria.server

import cats.effect.{IO, Resource}
import io.netty.handler.ssl.util.SelfSignedCertificate
import java.io.FileInputStream

import com.linecorp.armeria.client.{ClientFactory, WebClient}
import com.linecorp.armeria.common.HttpStatus
import munit.CatsEffectSuite
import org.http4s.HttpRoutes
import org.http4s.dsl.io._

class TlsServerSuite extends CatsEffectSuite with ServerFixture {
  override def munitFixtures = List(armeriaServerFixture)

  private val routes = HttpRoutes.of[IO] { case GET -> Root / "tls" =>
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
    assertEquals(client.get("/tls").aggregate().join().status(), HttpStatus.OK)
  }
}
