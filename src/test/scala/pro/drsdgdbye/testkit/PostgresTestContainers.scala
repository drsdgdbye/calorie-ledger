package pro.drsdgdbye.testkit

import com.dimafeng.testcontainers.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import pro.drsdgdbye.config.{AdminDbConfig, DbConfig}
import zio.*

/** Shared Postgres testcontainer wiring for integration tests. */
object PostgresTestContainers:

  /** Starts a shared Postgres 16 container, released when the layer's scope closes. */
  val containerLayer: ZLayer[Any, Throwable, PostgreSQLContainer] =
    ZLayer.scoped {
      ZIO.acquireRelease(
        ZIO.attemptBlocking {
          val container = PostgreSQLContainer(DockerImageName.parse("postgres:16"))
          container.start()
          container
        }
      )(container => ZIO.attemptBlocking(container.stop()).orDie)
    }

  /** Points [[DbConfig]] at the container (admin credentials are the container's superuser). */
  val dbConfigLayer: ZLayer[PostgreSQLContainer, Nothing, DbConfig] =
    ZLayer.fromFunction { (container: PostgreSQLContainer) =>
      DbConfig(
        url = container.jdbcUrl,
        user = container.username,
        password = container.password,
        poolSize = 2,
        admin = AdminDbConfig(container.jdbcUrl, container.username, container.password)
      )
    }
