package org.http4s
package server
package armeria

import cats.effect.ConcurrentEffect
import com.linecorp.armeria.common.SessionProtocol
import com.linecorp.armeria.server.Server
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
  protected def httpsPort: Try[Int] = Try(server.activeLocalPort(SessionProtocol.HTTPS))

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
