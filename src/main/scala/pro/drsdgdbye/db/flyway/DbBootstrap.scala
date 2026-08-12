package pro.drsdgdbye.db.flyway

import pro.drsdgdbye.config.DbConfig
import pro.drsdgdbye.db.JdbcUrl
import zio.*

import java.sql.{Connection, DriverManager}

/** Ensures the application role and database exist before Flyway runs, using the admin connection from the config.
  */
object DbBootstrap:

  /** JDBC driver for the Postgres URLs the application uses. Loaded explicitly so `DriverManager` does not depend on
    * ServiceLoader auto-registration, which is unreliable on threads whose context classloader differs (e.g. ZIO's
    * blocking pool).
    */
  private val jdbcDriverClass = "org.postgresql.Driver"

  /** Connects with the admin credentials and, if missing, creates the application role and database named after the
    * target JDBC URL. Idempotent: existing role/database are left untouched.
    */
  def ensureDatabase(cfg: DbConfig): Task[Unit] =
    val acquire = ZIO.attemptBlocking {
      val _ = Class.forName(jdbcDriverClass)
      DriverManager.getConnection(cfg.admin.url, cfg.admin.user, cfg.admin.password)
    }
    val release = (conn: Connection) => ZIO.attempt(conn.close()).orDie

    ZIO.acquireReleaseWith(acquire)(release) { conn =>
      ZIO.attemptBlocking {
        if !roleExists(conn, cfg.user) then createRole(conn, cfg.user, cfg.password)
        val dbName = JdbcUrl.databaseName(cfg.url) match
          case Right(name) => name
          case Left(err) => throw new IllegalArgumentException(err)
        if !databaseExists(conn, dbName) then createDatabase(conn, dbName, cfg.user)
      }
    }

  private def roleExists(conn: Connection, user: String): Boolean =
    val stmt = conn.prepareStatement("SELECT 1 FROM pg_roles WHERE rolname = ?")
    stmt.setString(1, user)
    try stmt.executeQuery().next()
    finally stmt.close()

  private def databaseExists(conn: Connection, dbName: String): Boolean =
    val stmt = conn.prepareStatement("SELECT 1 FROM pg_database WHERE datname = ?")
    stmt.setString(1, dbName)
    try stmt.executeQuery().next()
    finally stmt.close()

  private def createRole(conn: Connection, user: String, password: String): Unit =
    val escaped = password.replace("'", "''")
    execute(conn, s"""CREATE ROLE "${quoteIdentifier(user)}" LOGIN PASSWORD '$escaped'""")

  private def createDatabase(conn: Connection, dbName: String, owner: String): Unit =
    execute(conn, s"""CREATE DATABASE "${quoteIdentifier(dbName)}" OWNER "${quoteIdentifier(owner)}"""")

  private def execute(conn: Connection, sql: String): Unit =
    val stmt = conn.createStatement()
    try
      val _ = stmt.execute(sql)
    finally stmt.close()

  private def quoteIdentifier(name: String): String = name.replace("\"", "\"\"")
