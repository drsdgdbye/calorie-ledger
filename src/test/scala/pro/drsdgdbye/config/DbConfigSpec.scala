package pro.drsdgdbye.config

import zio.*
import zio.test.*

object DbConfigSpec extends ZIOSpecDefault:

  def spec: Spec[Any, Nothing] =
    suite("DbConfig")(
      test("loads from the classpath application.conf") {
        for result <- ZIO.scoped(DbConfig.layer.build.either)
        yield assertTrue(result.isRight)
      },
      test("exposes the configured defaults") {
        for
          either <- ZIO.scoped(DbConfig.layer.build.map(_.get[DbConfig]).either)
          result <- ZIO.succeed(either)
        yield assertTrue(
          result.exists(cfg =>
            cfg.url == "jdbc:postgresql://localhost:5432/calorie_ledger" &&
              cfg.user == "calorie" &&
              cfg.poolSize == 10 &&
              cfg.admin.url == "jdbc:postgresql://localhost:5432/postgres"
          )
        )
      }
    )
