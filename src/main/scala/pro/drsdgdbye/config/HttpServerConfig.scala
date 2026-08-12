package pro.drsdgdbye.config

import zio.*
import zio.config.magnolia.deriveConfig
import zio.config.typesafe.TypesafeConfigProvider

/** HTTP server binding settings. The port is overridable via the `SERVER_PORT` environment variable. */
final case class HttpServerConfig(port: Int)

object HttpServerConfig:
  // Config[HttpServerConfig] is derived automatically from the case class fields;
  // .nested("server") looks the fields up inside the "server" section of application.conf.
  private val descriptor: Config[HttpServerConfig] =
    deriveConfig[HttpServerConfig].nested("server")

  /** ZLayer loading the config from the classpath application.conf resource. */
  val layer: ZLayer[Any, Config.Error, HttpServerConfig] =
    ZLayer.fromZIO(
      TypesafeConfigProvider.fromResourcePath().load(descriptor)
    )
