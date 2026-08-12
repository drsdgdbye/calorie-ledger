package pro.drsdgdbye.db.flyway

import com.dimafeng.testcontainers.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import pro.drsdgdbye.config.{AdminDbConfig, DbConfig}
import zio.*
import zio.test.*
import zio.test.TestAspect.*

import java.sql.DriverManager

object DbBootstrapSpec extends ZIOSpecDefault:

  private val containerLayer: ZLayer[Any, Throwable, PostgreSQLContainer] =
    ZLayer.scoped {
      ZIO.acquireRelease(
        ZIO.attemptBlocking {
          val container = PostgreSQLContainer(DockerImageName.parse("postgres:16"))
          container.start()
          container
        }
      )(container => ZIO.attemptBlocking(container.stop()).orDie)
    }

  private def appConfig(container: PostgreSQLContainer): DbConfig =
    DbConfig(
      url = s"jdbc:postgresql://localhost:${container.mappedPort(5432)}/calorie_bootstrapped",
      user = "calorie_app",
      password = "secret",
      poolSize = 2,
      admin = AdminDbConfig(container.jdbcUrl, container.username, container.password)
    )

  private def roleExists(container: PostgreSQLContainer, role: String): Boolean =
    val conn = DriverManager.getConnection(container.jdbcUrl, container.username, container.password)
    try
      val stmt = conn.prepareStatement("SELECT 1 FROM pg_roles WHERE rolname = ?")
      stmt.setString(1, role)
      val exists = stmt.executeQuery().next()
      stmt.close()
      exists
    finally conn.close()

  private def databaseExists(container: PostgreSQLContainer, dbName: String): Boolean =
    val conn = DriverManager.getConnection(container.jdbcUrl, container.username, container.password)
    try
      val stmt = conn.prepareStatement("SELECT 1 FROM pg_database WHERE datname = ?")
      stmt.setString(1, dbName)
      val exists = stmt.executeQuery().next()
      stmt.close()
      exists
    finally conn.close()

  private def canConnectAs(cfg: DbConfig): Boolean =
    try
      val conn = DriverManager.getConnection(cfg.url, cfg.user, cfg.password)
      conn.close()
      true
    catch case _: Exception => false

  def spec: Spec[TestEnvironment, Throwable] =
    suite("DbBootstrap")(
      test("creates the application role and database when missing") {
        for
          container <- ZIO.service[PostgreSQLContainer]
          cfg = appConfig(container)
          _ <- DbBootstrap.ensureDatabase(cfg)
        yield assertTrue(
          roleExists(container, cfg.user),
          databaseExists(container, "calorie_bootstrapped"),
          canConnectAs(cfg)
        )
      },
      test("is idempotent when the role and database already exist") {
        for
          container <- ZIO.service[PostgreSQLContainer]
          cfg = appConfig(container)
          _ <- DbBootstrap.ensureDatabase(cfg)
          _ <- DbBootstrap.ensureDatabase(cfg)
        yield assertTrue(canConnectAs(cfg))
      },
      test("fails fast on an unsupported JDBC URL") {
        for
          container <- ZIO.service[PostgreSQLContainer]
          cfg = appConfig(container).copy(url = "jdbc:h2:mem:test")
          result <- DbBootstrap.ensureDatabase(cfg).exit
        yield assertTrue(result.isFailure)
      }
    ).provideLayer(containerLayer) @@ sequential @@ withLiveClock
