package pro.drsdgdbye

import pro.drsdgdbye.config.{DbConfig, HttpServerConfig}
import pro.drsdgdbye.domain.Constants
import zio.*
import zio.http.Server
import zio.logging.consoleLogger

/** Application entry point: boots the DB, then serves the HTTP API, Swagger UI and static frontend. All wiring lives in
  * [[App]].
  */
object Main extends ZIOAppDefault:

  override val bootstrap: ZLayer[ZIOAppArgs, Any, Any] =
    Runtime.removeDefaultLoggers >>> consoleLogger()

  /** HTTP server bound to the port from [[HttpServerConfig]] (default 10001, override via `SERVER_PORT`). */
  private val serverLayer: ZLayer[HttpServerConfig, Throwable, Server] =
    ZLayer.service[HttpServerConfig].flatMap { env =>
      Server.defaultWith(
        _.port(env.get.port).disableRequestStreaming(Constants.MaxHttpRequestBodyBytes)
      )
    }

  override def run: ZIO[Any, Throwable, Unit] =
    App.server.provide(App.appLayer, DbConfig.layer, HttpServerConfig.layer, serverLayer)
