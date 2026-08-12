package pro.drsdgdbye.db

import zio.test.*

object JdbcUrlSpec extends ZIOSpecDefault:

  def spec: Spec[Any, Nothing] =
    suite("JdbcUrl")(
      test("extracts database name from a standard URL") {
        assertTrue(
          JdbcUrl.databaseName("jdbc:postgresql://localhost:5432/calorie_ledger") == Right("calorie_ledger")
        )
      },
      test("ignores query parameters") {
        assertTrue(
          JdbcUrl.databaseName("jdbc:postgresql://localhost:5432/calorie_ledger?sslmode=require") == Right(
            "calorie_ledger"
          )
        )
      },
      test("rejects a URL without a database name") {
        assertTrue(JdbcUrl.databaseName("jdbc:postgresql://localhost:5432/").isLeft)
      },
      test("rejects an unsupported URL") {
        assertTrue(JdbcUrl.databaseName("jdbc:h2:mem:test").isLeft)
      },
      test("rejects a malformed URL") {
        assertTrue(JdbcUrl.databaseName("jdbc:postgresql://[broken").isLeft)
      }
    )
