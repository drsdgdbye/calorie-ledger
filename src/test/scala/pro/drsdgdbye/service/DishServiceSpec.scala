package pro.drsdgdbye.service

import pro.drsdgdbye.domain.*
import pro.drsdgdbye.testkit.{MockDishRepository, MockProductRepository, Samples}
import zio.*
import zio.test.*
import zio.test.Assertion.*

object DishServiceSpec extends ZIOSpecDefault:

  import Samples.*

  private def validInput: DishInput =
    DishInput("Плов", 1200, Vector(DishIngredientInput(5L, 300), DishIngredientInput(6L, 100)))

  private def withServices[A](
      dishes: MockDishRepository,
      products: MockProductRepository
  )(op: DishService => IO[DomainError, A]): IO[DomainError, A] =
    ZIO
      .serviceWithZIO[DishService](op)
      .provide(DishServiceLive.layer, ZLayer.succeed(dishes), ZLayer.succeed(products))

  private def repoFailure: IO[Throwable, Nothing] = ZIO.fail(new RuntimeException("boom"))

  private val plov = dishWithIngredients(
    1L,
    "Плов",
    1200,
    Vector(
      ingredient(5L, "Рис", 300, caloriesPer100 = 330, proteinPer100 = 7, fatPer100 = 1, carbsPer100 = 74),
      ingredient(6L, "Морковь", 100, caloriesPer100 = 120, proteinPer100 = 3, fatPer100 = 4, carbsPer100 = 5)
    )
  )

  def spec: Spec[Any, DomainError] =
    suite("DishService")(
      suite("list")(
        test("computes per100 calories for each dish") {
          val result = withServices(
            MockDishRepository(listF = (_, _, _, _) => ZIO.succeed(Vector(plov))),
            MockProductRepository()
          )(_.list(userId, None, 20, 0))
          assertZIO(result)(equalTo(Vector(DishListItemView(1L, "Плов", 1200, 93))))
        },
        test("delegates with a trimmed non-empty query") {
          for
            captured <- Ref.make(Option.empty[(Option[String], Int, Int)])
            dishes = MockDishRepository(listF =
              (_, q, l, o) => captured.set(Some((q, l, o))) *> ZIO.succeed(Vector.empty)
            )
            _ <- withServices(dishes, MockProductRepository())(_.list(userId, Some("  плов  "), 20, 0))
            args <- captured.get
          yield assertTrue(args.contains((Some("плов"), 20, 0)))
        },
        test("maps a repository failure to InternalError") {
          val result = withServices(
            MockDishRepository(listF = (_, _, _, _) => repoFailure),
            MockProductRepository()
          )(_.list(userId, None, 20, 0))
          assertZIO(result.either)(isLeft(equalTo(DomainError.InternalError)))
        }
      ),
      suite("get")(
        test("returns the detail with computed totals and per100") {
          val result = withServices(
            MockDishRepository(getF = (_, _) => ZIO.succeed(Some(plov))),
            MockProductRepository()
          )(_.get(userId, 1L))
          assertZIO(result)(equalTo(plovDetail))
        },
        test("fails with DishNotFound when the dish does not exist") {
          val result = withServices(MockDishRepository(), MockProductRepository())(_.get(userId, 42L))
          assertZIO(result.either)(isLeft(equalTo(DomainError.DishNotFound)))
        },
        test("rejects a non-positive id") {
          val result = withServices(MockDishRepository(), MockProductRepository())(_.get(userId, 0L))
          assertZIO(result.either)(isLeft(equalTo(DomainError.ValidationError)))
        },
        test("maps a repository failure to InternalError") {
          val result = withServices(
            MockDishRepository(getF = (_, _) => repoFailure),
            MockProductRepository()
          )(_.get(userId, 1L))
          assertZIO(result.either)(isLeft(equalTo(DomainError.InternalError)))
        }
      ),
      suite("create")(
        test("creates a dish delegating validated values and assigning positions") {
          for
            captured <- Ref.make(Option.empty[(String, Int, Vector[(Long, Int)])])
            dishes = MockDishRepository(
              createF = (_, name, weight, ingredients) =>
                captured.set(
                  Some(
                    (name.value, weight.value, ingredients.map(i => (i.productId.value, i.quantity.value)))
                  )
                ) *> ZIO.succeed(plov)
            )
            result <- withServices(dishes, MockProductRepository())(_.create(userId, validInput))
            args <- captured.get
          yield assertTrue(
            result == plovDetail,
            args.contains(("Плов", 1200, Vector((5L, 300), (6L, 100))))
          )
        },
        test("rejects an empty ingredient list") {
          val result = withServices(MockDishRepository(), MockProductRepository())(
            _.create(userId, validInput.copy(ingredients = Vector.empty))
          )
          assertZIO(result.either)(isLeft(equalTo(DomainError.ValidationError)))
        },
        test("rejects an invalid dish name") {
          val result = withServices(MockDishRepository(), MockProductRepository())(
            _.create(userId, validInput.copy(name = ""))
          )
          assertZIO(result.either)(isLeft(equalTo(DomainError.ValidationError)))
        },
        test("rejects a non-positive cooked weight") {
          val result = withServices(MockDishRepository(), MockProductRepository())(
            _.create(userId, validInput.copy(cookedWeightGrams = 0))
          )
          assertZIO(result.either)(isLeft(equalTo(DomainError.ValidationError)))
        },
        test("rejects an ingredient with a non-positive product id") {
          val input = validInput.copy(ingredients = Vector(DishIngredientInput(0L, 100)))
          val result = withServices(MockDishRepository(), MockProductRepository())(_.create(userId, input))
          assertZIO(result.either)(isLeft(equalTo(DomainError.ValidationError)))
        },
        test("rejects an ingredient with a non-positive quantity") {
          val input = validInput.copy(ingredients = Vector(DishIngredientInput(5L, 0)))
          val result = withServices(MockDishRepository(), MockProductRepository())(_.create(userId, input))
          assertZIO(result.either)(isLeft(equalTo(DomainError.ValidationError)))
        },
        test("rejects a reference to a missing product") {
          val result = withServices(
            MockDishRepository(),
            MockProductRepository(findExistingIdsF = (_, _) => ZIO.succeed(Vector.empty))
          )(_.create(userId, validInput))
          assertZIO(result.either)(isLeft(equalTo(DomainError.ValidationError)))
        },
        test("maps a repository failure to InternalError") {
          val result = withServices(
            MockDishRepository(createF = (_, _, _, _) => repoFailure),
            MockProductRepository()
          )(_.create(userId, validInput))
          assertZIO(result.either)(isLeft(equalTo(DomainError.InternalError)))
        }
      ),
      suite("update")(
        test("updates an existing dish") {
          val result = withServices(
            MockDishRepository(updateF = (_, _, _, _, _) => ZIO.succeed(Some(plov))),
            MockProductRepository()
          )(_.update(userId, 1L, validInput))
          assertZIO(result)(equalTo(plovDetail))
        },
        test("fails with DishNotFound when the dish does not exist") {
          val result = withServices(MockDishRepository(), MockProductRepository())(_.update(userId, 42L, validInput))
          assertZIO(result.either)(isLeft(equalTo(DomainError.DishNotFound)))
        },
        test("rejects a non-positive id") {
          val result = withServices(MockDishRepository(), MockProductRepository())(_.update(userId, 0L, validInput))
          assertZIO(result.either)(isLeft(equalTo(DomainError.ValidationError)))
        },
        test("maps a repository failure to InternalError") {
          val result = withServices(
            MockDishRepository(updateF = (_, _, _, _, _) => repoFailure),
            MockProductRepository()
          )(_.update(userId, 1L, validInput))
          assertZIO(result.either)(isLeft(equalTo(DomainError.InternalError)))
        }
      ),
      suite("delete")(
        test("deletes an existing dish") {
          val result = withServices(
            MockDishRepository(deleteF = (_, id) => ZIO.succeed(id.value == 9L)),
            MockProductRepository()
          )(_.delete(userId, 9L))
          assertZIO(result)(isUnit)
        },
        test("fails with DishNotFound when nothing was deleted") {
          val result = withServices(MockDishRepository(), MockProductRepository())(_.delete(userId, 9L))
          assertZIO(result.either)(isLeft(equalTo(DomainError.DishNotFound)))
        },
        test("rejects a non-positive id") {
          val result = withServices(MockDishRepository(), MockProductRepository())(_.delete(userId, 0L))
          assertZIO(result.either)(isLeft(equalTo(DomainError.ValidationError)))
        },
        test("maps a repository failure to InternalError") {
          val result = withServices(
            MockDishRepository(deleteF = (_, _) => repoFailure),
            MockProductRepository()
          )(_.delete(userId, 9L))
          assertZIO(result.either)(isLeft(equalTo(DomainError.InternalError)))
        }
      )
    )

  private val plovDetail: DishDetailView =
    DishDetailView(
      id = 1L,
      name = "Плов",
      cookedWeightGrams = 1200,
      ingredients = Vector(
        DishIngredientView(5L, "Рис", ProductUnit.GRAM, 300, 330, 7, 1, 74),
        DishIngredientView(6L, "Морковь", ProductUnit.GRAM, 100, 120, 3, 4, 5)
      ),
      totals = NutritionView(1110, 24, 7, 227),
      per100 = NutritionView(93, 2, 1, 19)
    )
