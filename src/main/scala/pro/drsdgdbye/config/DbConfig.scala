package pro.drsdgdbye.config

import zio._
import zio.config.magnolia.deriveConfig
import zio.config.typesafe.TypesafeConfigProvider

/** Credentials for the admin (superuser) connection used to bootstrap the database. */
final case class AdminDbConfig(
    url: String,
    user: String,
    password: String
)

/** Application database connection settings. */
final case class DbConfig(
    url: String,
    user: String,
    password: String,
    poolSize: Int,
    admin: AdminDbConfig
)

object DbConfig:
  // Config[DbConfig] is derived automatically from the case class fields;
  // .nested("db") looks the fields up inside the "db" section of application.conf.
  private val descriptor: Config[DbConfig] =
    deriveConfig[DbConfig].nested("db")

  /** ZLayer loading the config from the classpath application.conf resource. */
  val layer: ZLayer[Any, Config.Error, DbConfig] =
    ZLayer.fromZIO(
      TypesafeConfigProvider.fromResourcePath().load(descriptor)
    )
