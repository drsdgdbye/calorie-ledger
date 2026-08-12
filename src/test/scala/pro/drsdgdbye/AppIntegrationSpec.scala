package pro.drsdgdbye

import pro.drsdgdbye.config.DbConfig
import pro.drsdgdbye.domain.UserId
import pro.drsdgdbye.service.{DishService, ProductService}
import pro.drsdgdbye.testkit.{PostgresTestContainers, StubDishService, StubProductService}
import zio.*
import zio.http.Server
import zio.test.*
import zio.test.Assertion.*
import zio.test.TestAspect.*

object AppIntegrationSpec extends ZIOSpecDefault:

  private val dbLayer =
    PostgresTestContainers.containerLayer >>> PostgresTestContainers.dbConfigLayer

  def spec: Spec[TestEnvironment, Any] =
    suite("App integration")(
      test("migrates the schema into a fresh database") {
        assertZIO(App.migrate)(isUnit)
      },
      test("assembles the full service graph against a migrated database") {
        ZIO.scoped {
          for
            _ <- App.migrate
            env <- App.appLayer.build
            productService = env.get[ProductService]
            dishService = env.get[DishService]
            products <- productService.list(UserId.default, None, 10, 0)
            dishes <- dishService.list(UserId.default, None, 10, 0)
          yield assertTrue(products.isEmpty, dishes.isEmpty)
        }
      },
      test("the migration retry schedule evaluates") {
        assertTrue(App.retrySchedule != null)
      },
      test("serves the HTTP app until interrupted") {
        for result <- App.server
            .provideSome[DbConfig](
              ZLayer.succeed(StubProductService()),
              ZLayer.succeed(StubDishService()),
              Server.defaultWithPort(0)
            )
            .timeout(20.seconds)
            .either
        yield assertTrue(result == Right(None))
      }
    ).provideLayer(dbLayer) @@ sequential @@ withLiveClock
