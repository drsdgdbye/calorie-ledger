package pro.drsdgdbye.db.flyway

import org.flywaydb.core.Flyway
import pro.drsdgdbye.config.DbConfig
import zio.*

/** Applies the Flyway schema migrations from the classpath. */
object DbMigrator:

  /** Runs pending migrations against the configured database and logs how many were applied. */
  def migrate(cfg: DbConfig): Task[Unit] =
    ZIO
      .attemptBlocking {
        Flyway
          .configure()
          .dataSource(cfg.url, cfg.user, cfg.password)
          .locations("classpath:db/migration")
          .load()
          .migrate()
          .migrationsExecuted
      }
      .flatMap(applied => ZIO.logInfo(s"Flyway applied $applied migration(s)"))
