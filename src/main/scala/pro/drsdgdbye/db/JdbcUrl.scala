package pro.drsdgdbye.db

import java.net.URI

import scala.util.{Failure, Success, Try}

/** Parsing helpers for JDBC URLs. */
object JdbcUrl:

  /** Extracts the database name from a JDBC URL of the form `jdbc:postgresql://host:port/name`, ignoring any query
    * parameters.
    */
  def databaseName(url: String): Either[String, String] =
    if !url.startsWith("jdbc:postgresql://") then Left(s"Unsupported JDBC URL: $url")
    else
      Try(new URI(url.stripPrefix("jdbc:"))) match
        case Failure(_) => Left(s"Invalid JDBC URL: $url")
        case Success(uri) =>
          Option(uri.getPath)
            .map(_.stripPrefix("/"))
            .filter(_.nonEmpty)
            .toRight(s"Database name is missing in URL: $url")
