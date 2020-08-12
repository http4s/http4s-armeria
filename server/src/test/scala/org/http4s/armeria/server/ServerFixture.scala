package org.http4s.armeria.server

import cats.effect.ConcurrentEffect
import com.linecorp.armeria.common.SessionProtocol
import com.linecorp.armeria.server.Server
import java.net.URI
import org.scalatest.{BeforeAndAfterAll, BeforeAndAfterEach, Suite}
import scala.concurrent.duration._
import scala.util.Try

/** A fixture that starts and stops an Armeria server automatically
  * before and after executing a test or all tests .
  */
trait ServerFixture[F[_]] extends BeforeAndAfterAll with BeforeAndAfterEach {
  this: Suite =>

  private var armeriaServerWrapper: ArmeriaServer[F] = _
  private var server: Server = _
  private var releaseToken: F[Unit] = _

  /** Returns the [[ConcurrentEffect]] instance for the [[F]] type parameter */
  protected implicit def F: ConcurrentEffect[F]

  /** Configures the [[Server]] with the given [[ArmeriaServerBuilder]]. */
  protected def configureServer(customizer: ArmeriaServerBuilder[F]): ArmeriaServerBuilder[F]

  /** Returns whether the server should start and stop around each test method.
    * Implementations should override this method to return `true` to run around each method.
    * Otherwise, the server will start before all tests by default.
    */
  protected def runForEach: Boolean = false

  protected def httpPort: Try[Int] = Try(server.activeLocalPort(SessionProtocol.HTTP))
  protected def httpUri: Try[URI] = httpPort.map(port => URI.create(s"http://127.0.0.1:$port"))

  protected def httpsPort: Try[Int] = Try(server.activeLocalPort(SessionProtocol.HTTPS))
  protected def httpsUri: Try[URI] = httpsPort.map(port => URI.create(s"https://127.0.0.1:$port"))

  private def setUp(): Unit = {
    val serverBuilder = ArmeriaServerBuilder[F].withGracefulShutdownTimeout(0.seconds, 0.seconds)
    val configured = configureServer(serverBuilder)
    val allocated = F.toIO(configured.resource.allocated).unsafeRunSync()
    armeriaServerWrapper = allocated._1
    server = armeriaServerWrapper.server
    releaseToken = allocated._2
  }

  private def tearDown(): Unit = F.toIO(releaseToken).unsafeRunSync()

  override protected def beforeAll(): Unit = {
    if (!runForEach)
      setUp()
    super.beforeAll()
  }

  override protected def afterAll(): Unit = {
    if (!runForEach)
      tearDown()
    super.afterAll()
  }

  override protected def beforeEach(): Unit = {
    if (runForEach)
      setUp()
    super.beforeEach()
  }

  override protected def afterEach(): Unit = {
    if (runForEach)
      tearDown()
    super.afterEach()
  }
}
