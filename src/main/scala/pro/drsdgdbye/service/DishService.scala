package pro.drsdgdbye.service

import pro.drsdgdbye.calculation.{IngredientNutrients, NutrientCalculation}
import pro.drsdgdbye.domain.*
import pro.drsdgdbye.repository.{DishRepository, DishWithIngredients, NewDishIngredient, ProductRepository}
import zio.*

/** API payload for one ingredient of a dish. */
final case class DishIngredientInput(productId: Long, quantity: Int)

/** API payload for creating or updating a dish. */
final case class DishInput(name: String, cookedWeightGrams: Int, ingredients: Vector[DishIngredientInput])

/** Read model of an ingredient with its product nutrition for the dish detail. */
final case class DishIngredientView(
    productId: Long,
    productName: String,
    unit: ProductUnit,
    quantity: Int,
    caloriesPer100: Int,
    proteinPer100: Int,
    fatPer100: Int,
    carbsPer100: Int
)

/** Computed nutritional values of a dish. */
final case class NutritionView(calories: Int, protein: Int, fat: Int, carbs: Int)

/** Compact dish row for list responses, carrying the per-100g calories. */
final case class DishListItemView(id: Long, name: String, cookedWeightGrams: Int, caloriesPer100: Int)

/** Full dish response with ingredients and computed nutrition. */
final case class DishDetailView(
    id: Long,
    name: String,
    cookedWeightGrams: Int,
    ingredients: Vector[DishIngredientView],
    totals: NutritionView,
    per100: NutritionView
)

/** Use-case operations over dishes, hiding repositories and validation behind the domain. */
trait DishService:
  def list(userId: UserId, query: Option[String], limit: Int, offset: Int): IO[DomainError, Vector[DishListItemView]]

  def get(userId: UserId, dishId: Long): IO[DomainError, DishDetailView]

  def create(userId: UserId, input: DishInput): IO[DomainError, DishDetailView]

  def update(userId: UserId, dishId: Long, input: DishInput): IO[DomainError, DishDetailView]

  def delete(userId: UserId, dishId: Long): IO[DomainError, Unit]

/** ZLayer wiring for the live [[DishService]] implementation. */
object DishServiceLive:
  val layer: ZLayer[DishRepository & ProductRepository, Nothing, DishService] =
    ZLayer.fromFunction(DishServiceLive(_, _))

final case class DishServiceLive(dishes: DishRepository, products: ProductRepository) extends DishService:
  private def repoCall[A](zio: Task[A]): IO[DomainError, A] =
    zio
      .tapErrorCause(cause => ZIO.logErrorCause("Repository call failed", cause))
      .mapError(_ => DomainError.InternalError)

  private def validate[A](e: Either[DomainError, A]): IO[DomainError, A] =
    ZIO.fromEither(e)

  private def decode[A](e: Either[DomainError, A]): A =
    e.fold(_ => throw IllegalStateException("Invariant violated"), identity)

  /** Projects ingredient rows into the pure calculation input shape. */
  private def nutrientInputs(dwi: DishWithIngredients): Vector[IngredientNutrients] =
    dwi.ingredients.map(i =>
      IngredientNutrients(
        quantity = i.quantity.value,
        caloriesPer100 = i.caloriesPer100.value,
        proteinPer100 = i.proteinPer100.value,
        fatPer100 = i.fatPer100.value,
        carbsPer100 = i.carbsPer100.value
      )
    )

  /** Builds the API detail view, computing totals and per-100g nutrition from ingredients. */
  private def toDetail(dwi: DishWithIngredients): DishDetailView =
    val inputs = nutrientInputs(dwi)
    val totals = NutrientCalculation.totals(inputs)
    val per100 = NutrientCalculation.per100(inputs, dwi.dish.cookedWeightGrams.value)
    DishDetailView(
      id = dwi.dish.id.value,
      name = dwi.dish.name.value,
      cookedWeightGrams = dwi.dish.cookedWeightGrams.value,
      ingredients = dwi.ingredients.map(i =>
        DishIngredientView(
          productId = i.productId.value,
          productName = i.productName.value,
          unit = i.unit,
          quantity = i.quantity.value,
          caloriesPer100 = i.caloriesPer100.value,
          proteinPer100 = i.proteinPer100.value,
          fatPer100 = i.fatPer100.value,
          carbsPer100 = i.carbsPer100.value
        )
      ),
      totals = NutritionView(totals.calories, totals.protein, totals.fat, totals.carbs),
      per100 = NutritionView(per100.calories, per100.protein, per100.fat, per100.carbs)
    )

  /** Validates ingredient payloads and returns domain-typed (product, quantity) pairs, rejecting an empty ingredient
    * list.
    */
  private def validateIngredients(input: Vector[DishIngredientInput]): IO[DomainError, Vector[(ProductId, Quantity)]] =
    if input.isEmpty then ZIO.fail(DomainError.ValidationError)
    else
      ZIO.foreach(input) { i =>
        for
          productId <- validate(ProductId.from(i.productId))
          quantity <- validate(Quantity.from(i.quantity))
        yield (productId, quantity)
      }

  /** Ensures every referenced product exists and is owned by the user, then assigns ingredients their ordering
    * positions. Fails with [[DomainError.ValidationError]] if any product is missing.
    */
  private def ensureProductsExist(
      userId: UserId,
      pairs: Vector[(ProductId, Quantity)]
  ): IO[DomainError, Vector[NewDishIngredient]] =
    val ids = pairs.map(_._1).distinct
    for
      existing <- repoCall(products.findExistingIds(userId, ids))
      _ <- if existing.size == ids.size then ZIO.unit else ZIO.fail(DomainError.ValidationError)
    yield pairs.zipWithIndex.map { case ((productId, quantity), idx) =>
      NewDishIngredient(productId, quantity, decode(Position.from(idx)))
    }

  def list(userId: UserId, query: Option[String], limit: Int, offset: Int): IO[DomainError, Vector[DishListItemView]] =
    repoCall(dishes.listWithIngredients(userId, query.map(_.trim).filter(_.nonEmpty), limit, offset))
      .map(_.map { dwi =>
        val per100 = NutrientCalculation.per100(nutrientInputs(dwi), dwi.dish.cookedWeightGrams.value)
        DishListItemView(dwi.dish.id.value, dwi.dish.name.value, dwi.dish.cookedWeightGrams.value, per100.calories)
      })

  def get(userId: UserId, dishId: Long): IO[DomainError, DishDetailView] =
    for
      id <- validate(DishId.from(dishId))
      dwi <- repoCall(dishes.getWithIngredients(userId, id))
      detail <- ZIO.fromOption(dwi.map(toDetail)).mapError(_ => DomainError.DishNotFound)
    yield detail

  def create(userId: UserId, input: DishInput): IO[DomainError, DishDetailView] =
    for
      name <- validate(DishName.from(input.name))
      weight <- validate(Weight.from(input.cookedWeightGrams))
      pairs <- validateIngredients(input.ingredients)
      newIngredients <- ensureProductsExist(userId, pairs)
      dwi <- repoCall(dishes.create(userId, name, weight, newIngredients))
    yield toDetail(dwi)

  def update(userId: UserId, dishId: Long, input: DishInput): IO[DomainError, DishDetailView] =
    for
      id <- validate(DishId.from(dishId))
      name <- validate(DishName.from(input.name))
      weight <- validate(Weight.from(input.cookedWeightGrams))
      pairs <- validateIngredients(input.ingredients)
      newIngredients <- ensureProductsExist(userId, pairs)
      updated <- repoCall(dishes.update(userId, id, name, weight, newIngredients))
      detail <- ZIO.fromOption(updated.map(toDetail)).mapError(_ => DomainError.DishNotFound)
    yield detail

  def delete(userId: UserId, dishId: Long): IO[DomainError, Unit] =
    for
      id <- validate(DishId.from(dishId))
      deleted <- repoCall(dishes.delete(userId, id))
      _ <- if deleted then ZIO.unit else ZIO.fail(DomainError.DishNotFound)
    yield ()
