package pro.drsdgdbye.service

import pro.drsdgdbye.domain.*
import pro.drsdgdbye.repository.NewProductRow
import pro.drsdgdbye.testkit.{MockProductRepository, Samples}
import zio.*
import zio.test.*
import zio.test.Assertion.*

object ProductServiceSpec extends ZIOSpecDefault:

  import Samples.*

  private def validInput: ProductInput =
    ProductInput("Рис", Some("Крупы"), ProductUnit.GRAM, 330, 7, 1, 74)

  private def withService[A](mock: MockProductRepository)(
      op: ProductService => IO[DomainError, A]): IO[DomainError, A] =
    ZIO.serviceWithZIO[ProductService](op).provide(ProductServiceLive.layer, ZLayer.succeed(mock))

  private def repoFailure: IO[Throwable, Nothing] = ZIO.fail(new RuntimeException("boom"))

  def spec: Spec[Any, DomainError] =
    suite("ProductService")(
      suite("list")(
        test("delegates with a trimmed non-empty query") {
          for
            captured <- Ref.make(Option.empty[(Option[String], Int, Int)])
            mock = MockProductRepository(
              listF = (_, q, l, o) => captured.set(Some((q, l, o))) *> ZIO.succeed(Vector(product(1L, "Рис")))
            )
            result <- withService(mock)(_.list(userId, Some("  рис  "), 20, 0))
            args <- captured.get
          yield assertTrue(
            result.map(_.name.value) == Vector("Рис"),
            args.contains((Some("рис"), 20, 0))
          )
        },
        test("passes None when the query is blank") {
          for
            captured <- Ref.make(Option.empty[(Option[String], Int, Int)])
            mock = MockProductRepository(listF =
              (_, q, l, o) => captured.set(Some((q, l, o))) *> ZIO.succeed(Vector.empty)
            )
            _ <- withService(mock)(_.list(userId, Some("   "), 10, 5))
            args <- captured.get
          yield assertTrue(args.contains((None, 10, 5)))
        },
        test("maps a repository failure to InternalError") {
          val result =
            withService(MockProductRepository(listF = (_, _, _, _) => repoFailure))(_.list(userId, None, 20, 0))
          assertZIO(result.either)(isLeft(equalTo(DomainError.InternalError)))
        }
      ),
      suite("categories")(
        test("returns the distinct categories") {
          val result = withService(
            MockProductRepository(categoriesF = _ => ZIO.succeed(Vector("Мясо", "Крупы")))
          )(_.categories(userId))
          assertZIO(result)(equalTo(Vector("Мясо", "Крупы")))
        },
        test("maps a repository failure to InternalError") {
          val result = withService(MockProductRepository(categoriesF = _ => repoFailure))(_.categories(userId))
          assertZIO(result.either)(isLeft(equalTo(DomainError.InternalError)))
        }
      ),
      suite("create")(
        test("creates a product delegating the validated values") {
          for
            captured <- Ref.make(Option.empty[(String, Option[String], ProductUnit, Int, Int, Int, Int)])
            expected = product(1L, "Рис")
            mock = MockProductRepository(
              createF = (_, name, category, unit, c, p, f, cb) =>
                captured.set(Some((name.value, category.map(_.value), unit, c.value, p.value, f.value, cb.value))) *>
                  ZIO.succeed(expected)
            )
            result <- withService(mock)(_.create(userId, validInput))
            args <- captured.get
          yield assertTrue(
            result == expected,
            args.contains(("Рис", Some("Крупы"), ProductUnit.GRAM, 330, 7, 1, 74))
          )
        },
        test("creates a product without a category") {
          val result = withService(
            MockProductRepository(
              createF =
                (_, name, category, _, _, _, _, _) => ZIO.succeed(product(2L, name.value, category.map(_.value)))
            )
          )(_.create(userId, validInput.copy(category = None)))
          assertZIO(result)(equalTo(product(2L, "Рис")))
        },
        test("rejects an empty name") {
          val result = withService(MockProductRepository())(_.create(userId, validInput.copy(name = "  ")))
          assertZIO(result.either)(isLeft(equalTo(DomainError.ValidationError)))
        },
        test("rejects a name longer than the limit") {
          val result = withService(MockProductRepository())(
            _.create(userId, validInput.copy(name = "x" * (Constants.MaxNameLength + 1)))
          )
          assertZIO(result.either)(isLeft(equalTo(DomainError.ValidationError)))
        },
        test("rejects an invalid category") {
          val result = withService(MockProductRepository())(_.create(userId, validInput.copy(category = Some("  "))))
          assertZIO(result.either)(isLeft(equalTo(DomainError.ValidationError)))
        },
        test("rejects negative calories") {
          val result = withService(MockProductRepository())(_.create(userId, validInput.copy(caloriesPer100 = -1)))
          assertZIO(result.either)(isLeft(equalTo(DomainError.ValidationError)))
        },
        test("rejects calories above the limit") {
          val result = withService(MockProductRepository())(
            _.create(userId, validInput.copy(caloriesPer100 = Constants.MaxNutrientPer100 + 1))
          )
          assertZIO(result.either)(isLeft(equalTo(DomainError.ValidationError)))
        },
        test("rejects negative protein") {
          val result = withService(MockProductRepository())(_.create(userId, validInput.copy(proteinPer100 = -1)))
          assertZIO(result.either)(isLeft(equalTo(DomainError.ValidationError)))
        },
        test("rejects negative fat") {
          val result = withService(MockProductRepository())(_.create(userId, validInput.copy(fatPer100 = -1)))
          assertZIO(result.either)(isLeft(equalTo(DomainError.ValidationError)))
        },
        test("rejects negative carbs") {
          val result = withService(MockProductRepository())(_.create(userId, validInput.copy(carbsPer100 = -1)))
          assertZIO(result.either)(isLeft(equalTo(DomainError.ValidationError)))
        },
        test("maps a repository failure to InternalError") {
          val result = withService(MockProductRepository(createF = (_, _, _, _, _, _, _, _) => repoFailure))(
            _.create(userId, validInput)
          )
          assertZIO(result.either)(isLeft(equalTo(DomainError.InternalError)))
        }
      ),
      suite("update")(
        test("updates an existing product") {
          val updated = product(3L, "Рис длиннозёрный", Some("Крупы"))
          val result = withService(
            MockProductRepository(
              updateF = (_, id, name, category, _, _, _, _, _) =>
                ZIO.succeed(Some(product(id.value, name.value, category.map(_.value))))
            )
          )(_.update(userId, 3L, validInput.copy(name = "Рис длиннозёрный")))
          assertZIO(result)(equalTo(updated))
        },
        test("fails with ProductNotFound when the product does not exist") {
          val result = withService(MockProductRepository())(_.update(userId, 42L, validInput))
          assertZIO(result.either)(isLeft(equalTo(DomainError.ProductNotFound)))
        },
        test("rejects a non-positive id") {
          val result = withService(MockProductRepository())(_.update(userId, 0L, validInput))
          assertZIO(result.either)(isLeft(equalTo(DomainError.ValidationError)))
        },
        test("rejects invalid input") {
          val result = withService(MockProductRepository())(_.update(userId, 42L, validInput.copy(name = "")))
          assertZIO(result.either)(isLeft(equalTo(DomainError.ValidationError)))
        },
        test("maps a repository failure to InternalError") {
          val result = withService(MockProductRepository(updateF = (_, _, _, _, _, _, _, _, _) => repoFailure))(
            _.update(userId, 42L, validInput)
          )
          assertZIO(result.either)(isLeft(equalTo(DomainError.InternalError)))
        }
      ),
      suite("archive")(
        test("archives an existing product") {
          val result = withService(
            MockProductRepository(archiveF = (_, id) => ZIO.succeed(id.value == 7L))
          )(_.archive(userId, 7L))
          assertZIO(result)(isUnit)
        },
        test("fails with ProductNotFound when nothing was archived") {
          val result = withService(MockProductRepository())(_.archive(userId, 7L))
          assertZIO(result.either)(isLeft(equalTo(DomainError.ProductNotFound)))
        },
        test("rejects a non-positive id") {
          val result = withService(MockProductRepository())(_.archive(userId, 0L))
          assertZIO(result.either)(isLeft(equalTo(DomainError.ValidationError)))
        },
        test("maps a repository failure to InternalError") {
          val result = withService(MockProductRepository(archiveF = (_, _) => repoFailure))(_.archive(userId, 7L))
          assertZIO(result.either)(isLeft(equalTo(DomainError.InternalError)))
        }
      ),
      suite("import")(
        test("imports valid records in a single batch") {
          for
            captured <- Ref.make(Option.empty[Vector[NewProductRow]])
            mock = MockProductRepository(
              createBatchF = (_, rows) => captured.set(Some(rows)) *> ZIO.succeed(rows.size)
            )
            result <- withService(mock)(
              _.importProducts(userId, Vector(Right(validInput), Right(validInput.copy(name = "Гречка"))))
            )
            rows <- captured.get
          yield assertTrue(
            result == ProductImportResult(imported = 2, errors = Vector.empty),
            rows.exists(_.size == 2),
            rows.exists(_.map(_.name) == Vector("Рис", "Гречка")),
            rows.exists(_.forall(_.unit == "GRAM"))
          )
        },
        test("reports per-record structural and field codes") {
          val items = Vector(
            Left(ImportIssueCode.InvalidUnit),
            Right(validInput.copy(name = "  ")),
            Right(validInput.copy(caloriesPer100 = -1)),
            Right(validInput.copy(proteinPer100 = Constants.MaxNutrientPer100 + 1))
          )
          for
            createCalled <- Ref.make(false)
            mock = MockProductRepository(createBatchF = (_, _) => createCalled.set(true) *> ZIO.succeed(0))
            result <- withService(mock)(_.importProducts(userId, items))
            called <- createCalled.get
          yield assertTrue(
            result.imported == 0,
            result.errors == Vector(
              ImportItemError(0, ImportIssueCode.InvalidUnit),
              ImportItemError(1, ImportIssueCode.InvalidName),
              ImportItemError(2, ImportIssueCode.InvalidCalories),
              ImportItemError(3, ImportIssueCode.InvalidProtein)
            ),
            !called
          )
        },
        test("skips duplicates already present in the catalog") {
          val mock = MockProductRepository(
            existingKeysF = (_, _) => ZIO.succeed(Set(("Рис", Some("Крупы"))))
          )
          val result = withService(mock)(_.importProducts(userId, Vector(Right(validInput))))
          assertZIO(result)(
            equalTo(ProductImportResult(imported = 0, errors = Vector(ImportItemError(0, ImportIssueCode.Duplicate))))
          )
        },
        test("skips records repeated within the file") {
          val mock = MockProductRepository(createBatchF = (_, rows) => ZIO.succeed(rows.size))
          val result = withService(mock)(
            _.importProducts(userId, Vector(Right(validInput), Right(validInput)))
          )
          assertZIO(result)(
            equalTo(
              ProductImportResult(
                imported = 1,
                errors = Vector(ImportItemError(1, ImportIssueCode.Duplicate))
              )
            )
          )
        },
        test("dedup key is case-sensitive and includes the category") {
          for
            captured <- Ref.make(Option.empty[Vector[NewProductRow]])
            mock = MockProductRepository(
              existingKeysF = (_, _) => ZIO.succeed(Set(("рис", Some("Крупы")), ("Рис", Some("Напитки")))),
              createBatchF = (_, rows) => captured.set(Some(rows)) *> ZIO.succeed(rows.size)
            )
            result <- withService(mock)(
              _.importProducts(userId, Vector(Right(validInput.copy(category = Some("Крупы")))))
            )
            rows <- captured.get
          yield assertTrue(
            result.imported == 1,
            result.errors.isEmpty,
            rows.exists(_.size == 1)
          )
        },
        test("rejects an empty import") {
          val result = withService(MockProductRepository())(_.importProducts(userId, Vector.empty))
          assertZIO(result.either)(isLeft(equalTo(DomainError.ValidationError)))
        },
        test("rejects an oversized import") {
          val items = Vector.fill(Constants.MaxImportBatchSize + 1)(Right(validInput))
          val result = withService(MockProductRepository())(_.importProducts(userId, items))
          assertZIO(result.either)(isLeft(equalTo(DomainError.ValidationError)))
        },
        test("maps an existingKeys failure to InternalError") {
          val mock = MockProductRepository(existingKeysF = (_, _) => repoFailure)
          val result = withService(mock)(_.importProducts(userId, Vector(Right(validInput))))
          assertZIO(result.either)(isLeft(equalTo(DomainError.InternalError)))
        },
        test("maps a createBatch failure to InternalError") {
          val mock = MockProductRepository(createBatchF = (_, _) => repoFailure)
          val result = withService(mock)(_.importProducts(userId, Vector(Right(validInput))))
          assertZIO(result.either)(isLeft(equalTo(DomainError.InternalError)))
        }
      )
    )
