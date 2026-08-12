package pro.drsdgdbye.service

import pro.drsdgdbye.domain.*
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
      )
    )
