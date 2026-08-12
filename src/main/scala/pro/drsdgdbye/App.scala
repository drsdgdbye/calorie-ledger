package pro.drsdgdbye

import pro.drsdgdbye.api.{Api, StaticRoutes}
import pro.drsdgdbye.config.DbConfig
import pro.drsdgdbye.db.QuillContext
import pro.drsdgdbye.db.flyway.{DbBootstrap, DbMigrator}
import pro.drsdgdbye.repository.{DishRepositoryLive, ProductRepositoryLive}
import pro.drsdgdbye.service.{DishService, DishServiceLive, ProductService, ProductServiceLive}
import sttp.tapir.server.ServerEndpoint
import sttp.tapir.server.ziohttp.ZioHttpInterpreter
import sttp.tapir.swagger.SwaggerUIOptions
import sttp.tapir.swagger.bundle.SwaggerInterpreter
import zio.*
import zio.http.{Response, Routes, Server}

/** Application assembly: DB migration, service graph and HTTP routes. Kept separate from the ZIO entry point ([[Main]])
  * so the wiring can be exercised in tests without starting the process.
  */
object App:

  /** Retries the whole migration step up to 9 times, once per second. */
  val retrySchedule: Schedule[Any, Any, Any] =
    Schedule.recurs(9) && Schedule.fixed(1.second)

  /** Bootstraps the database (creates role/db if needed) and applies Flyway migrations. */
  val migrate: ZIO[DbConfig, Throwable, Unit] =
    for
      cfg <- ZIO.service[DbConfig]
      _ <- ZIO.logInfo(s"Applying database migrations for ${cfg.url}")
      _ <- DbBootstrap.ensureDatabase(cfg)
      _ <- DbMigrator.migrate(cfg)
    yield ()

  /** Assembles the service graph: data source -> repositories -> services. The [[DbConfig]] is taken from the
    * environment (provided by [[Main]] from application.conf, or overridden in tests) so the graph never reads the
    * config itself.
    */
  val appLayer: ZLayer[DbConfig, Throwable, ProductService & DishService] =
    QuillContext.layer
      >+> ProductRepositoryLive.layer
      >+> DishRepositoryLive.layer
      >+> ProductServiceLive.layer
      >+> DishServiceLive.layer

  /** Swagger UI mounted at /swagger-ui, generated from the tapir endpoint descriptions. */
  def swaggerRoutes: Routes[Any, Response] =
    val endpoints: List[ServerEndpoint[Any, Task]] =
      SwaggerInterpreter(swaggerUIOptions = SwaggerUIOptions.default.pathPrefix(List("swagger-ui")))
        .fromEndpoints[Task](Api.publicEndpoints, "Calorie Ledger", "1.0")
    ZioHttpInterpreter().toHttp(endpoints)

  /** HTTP routes: API + Swagger UI + static frontend. */
  def routes(productService: ProductService, dishService: DishService): Routes[Any, Response] =
    ZioHttpInterpreter().toHttp(Api.serverEndpoints(productService, dishService)) ++
      swaggerRoutes ++
      StaticRoutes.routes

  /** Migrates the DB, then serves API + Swagger + static files on the configured server. */
  val server: ZIO[DbConfig & ProductService & DishService & Server, Throwable, Unit] =
    for
      _ <- migrate.retry(retrySchedule)
      productService <- ZIO.service[ProductService]
      dishService <- ZIO.service[DishService]
      _ <- Server.serve(routes(productService, dishService))
    yield ()
