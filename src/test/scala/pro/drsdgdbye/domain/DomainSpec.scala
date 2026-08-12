package pro.drsdgdbye.domain

import java.time.Instant
import zio.test.*

object DomainSpec extends ZIOSpecDefault:

  private def decode[A](e: Either[DomainError, A]): A =
    e.fold(e => throw IllegalStateException(s"Test data violates domain invariants: $e"), identity)

  private val now: Instant = Instant.parse("2024-01-01T00:00:00Z")

  def spec: Spec[Any, Nothing] =
    suite("Domain")(
      suite("opaque type validation")(
        test("positive ids accept any value greater than zero") {
          assertTrue(
            ProductId.from(1L).isRight,
            ProductId.from(Long.MaxValue).isRight,
            ProductId.from(0L).isLeft,
            ProductId.from(-1L).isLeft,
            DishId.from(1L).isRight,
            DishId.from(0L).isLeft,
            DishIngredientId.from(1L).isRight,
            DishIngredientId.from(0L).isLeft,
            UserId.from(1L).isRight,
            UserId.from(0L).isLeft
          )
        },
        test("names are trimmed, non-empty and bounded by MaxNameLength") {
          assertTrue(
            ProductName.from("  рис  ").map(_.value) == Right("рис"),
            ProductName.from("").isLeft,
            ProductName.from("   ").isLeft,
            ProductName.from("x" * Constants.MaxNameLength).isRight,
            ProductName.from("x" * (Constants.MaxNameLength + 1)).isLeft,
            DishName.from("плов").isRight,
            DishName.from("").isLeft,
            DishName.from("x" * (Constants.MaxNameLength + 1)).isLeft,
            Username.from("default").isRight,
            Username.from("").isLeft,
            Username.from("x" * (Constants.MaxNameLength + 1)).isLeft
          )
        },
        test("category names are bounded by MaxCategoryLength") {
          assertTrue(
            CategoryName.from("каши").isRight,
            CategoryName.from("  молочка  ").map(_.value) == Right("молочка"),
            CategoryName.from("").isLeft,
            CategoryName.from("x" * Constants.MaxCategoryLength).isRight,
            CategoryName.from("x" * (Constants.MaxCategoryLength + 1)).isLeft
          )
        },
        test("nutrients are bounded between zero and MaxNutrientPer100") {
          assertTrue(
            Calories.from(0).isRight,
            Calories.from(Constants.MaxNutrientPer100).isRight,
            Calories.from(-1).isLeft,
            Calories.from(Constants.MaxNutrientPer100 + 1).isLeft,
            Carbs.from(0).isRight,
            Carbs.from(-1).isLeft,
            Carbs.from(Constants.MaxNutrientPer100 + 1).isLeft,
            Fat.from(0).isRight,
            Fat.from(-1).isLeft,
            Fat.from(Constants.MaxNutrientPer100 + 1).isLeft,
            Protein.from(0).isRight,
            Protein.from(-1).isLeft,
            Protein.from(Constants.MaxNutrientPer100 + 1).isLeft
          )
        },
        test("cooked weight is positive and bounded by MaxCookedWeightGrams") {
          assertTrue(
            Weight.from(1).isRight,
            Weight.from(Constants.MaxCookedWeightGrams).isRight,
            Weight.from(0).isLeft,
            Weight.from(-1).isLeft,
            Weight.from(Constants.MaxCookedWeightGrams + 1).isLeft
          )
        },
        test("ingredient quantity is positive and bounded by MaxQuantity") {
          assertTrue(
            Quantity.from(1).isRight,
            Quantity.from(Constants.MaxQuantity).isRight,
            Quantity.from(0).isLeft,
            Quantity.from(-1).isLeft,
            Quantity.from(Constants.MaxQuantity + 1).isLeft
          )
        },
        test("ingredient position is non-negative and unbounded") {
          assertTrue(
            Position.from(0).isRight,
            Position.from(Int.MaxValue).isRight,
            Position.from(-1).isLeft
          )
        },
        test("the default user is the seeded id") {
          assertTrue(UserId.default.value == Constants.DefaultUserId)
        },
        test("product units are GRAM and ML") {
          assertTrue(
            ProductUnit.values.toSet == Set(ProductUnit.GRAM, ProductUnit.ML),
            ProductUnit.GRAM.toString == "GRAM",
            ProductUnit.ML.toString == "ML"
          )
        }
      ),
      suite("case classes")(
        test("products can be constructed with and without a category") {
          val userId = UserId.default
          val withCategory = Product(
            id = decode(ProductId.from(1L)),
            userId = userId,
            name = decode(ProductName.from("Рис")),
            category = Some(decode(CategoryName.from("Крупы"))),
            unit = ProductUnit.GRAM,
            caloriesPer100 = decode(Calories.from(330)),
            proteinPer100 = decode(Protein.from(7)),
            fatPer100 = decode(Fat.from(1)),
            carbsPer100 = decode(Carbs.from(74)),
            isArchived = true,
            createdAt = now,
            updatedAt = now
          )
          val withoutCategory = withCategory.copy(category = None, isArchived = false)
          assertTrue(
            withCategory.id.value == 1L,
            withCategory.category.map(_.value) == Some("Крупы"),
            withCategory.isArchived,
            withoutCategory.category.isEmpty,
            !withoutCategory.isArchived
          )
        },
        test("dishes, ingredients and users can be constructed") {
          val userId = UserId.default
          val dish = Dish(
            id = decode(DishId.from(1L)),
            userId = userId,
            name = decode(DishName.from("Плов")),
            cookedWeightGrams = decode(Weight.from(1200)),
            createdAt = now,
            updatedAt = now
          )
          val ingredient = DishIngredient(
            id = decode(DishIngredientId.from(1L)),
            dishId = dish.id,
            productId = decode(ProductId.from(5L)),
            quantity = decode(Quantity.from(300)),
            position = decode(Position.from(0))
          )
          val user = User(id = userId, username = decode(Username.from("default")), createdAt = now)
          assertTrue(
            dish.cookedWeightGrams.value == 1200,
            ingredient.quantity.value == 300,
            ingredient.position.value == 0,
            user.username.value == "default"
          )
        }
      )
    )
