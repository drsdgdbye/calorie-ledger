package pro.drsdgdbye.db

import com.zaxxer.hikari.HikariDataSource
import io.getquill.jdbczio.Quill
import pro.drsdgdbye.config.DbConfig
import zio.*

import javax.sql.DataSource

/** Builds the Quill PostgreSQL context and its backing connection pool from [[DbConfig]]. */
object QuillContext:
  type Ctx = Quill.Postgres[DbNaming.type]

  /** Creates a HikariCP data source from the config, closed automatically on scope exit. */
  val dataSourceLayer: ZLayer[DbConfig, Throwable, DataSource] =
    ZLayer.scoped {
      for
        cfg <- ZIO.service[DbConfig]
        ds <- ZIO.fromAutoCloseable(
          ZIO.attempt {
            val hikari = new HikariDataSource()
            hikari.setJdbcUrl(cfg.url)
            hikari.setUsername(cfg.user)
            hikari.setPassword(cfg.password)
            hikari.setMaximumPoolSize(cfg.poolSize)
            hikari
          }
        )
      yield ds
    }

  /** Quill context wired to the [[DbNaming]] strategy. */
  val ctxLayer: ZLayer[DataSource, Throwable, Ctx] =
    Quill.Postgres.fromNamingStrategy(DbNaming)

  /** Combined layer: data source then context. */
  val layer: ZLayer[DbConfig, Throwable, Ctx] =
    dataSourceLayer >>> ctxLayer
