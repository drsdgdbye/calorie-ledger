package pro.drsdgdbye.testkit

import pro.drsdgdbye.domain.*
import pro.drsdgdbye.repository.*
import zio.*

import java.time.Instant

/** Test data builders producing domain values with valid invariants. */
object Samples:

  val userId: UserId = UserId.default
  val now: Instant = Instant.parse("2024-01-01T00:00:00Z")

  private def decode[A](e: Either[DomainError, A]): A =
    e.fold(e => throw IllegalStateException(s"Test data violates domain invariants: $e"), identity)

  def product(id: Long, name: String, category: Option[String] = None, isArchived: Boolean = false): Product =
    Product(
      id = decode(ProductId.from(id)),
      userId = userId,
      name = decode(ProductName.from(name)),
      category = category.map(c => decode(CategoryName.from(c))),
      unit = ProductUnit.GRAM,
      caloriesPer100 = decode(Calories.from(330)),
      proteinPer100 = decode(Protein.from(7)),
      fatPer100 = decode(Fat.from(1)),
      carbsPer100 = decode(Carbs.from(74)),
      isArchived = isArchived,
      createdAt = now,
      updatedAt = now
    )

  def ingredient(
      productId: Long,
      productName: String,
      quantity: Int,
      caloriesPer100: Int = 330,
      proteinPer100: Int = 7,
      fatPer100: Int = 1,
      carbsPer100: Int = 74
  ): IngredientWithProduct =
    IngredientWithProduct(
      productId = decode(ProductId.from(productId)),
      productName = decode(ProductName.from(productName)),
      unit = ProductUnit.GRAM,
      quantity = decode(Quantity.from(quantity)),
      position = decode(Position.from(0)),
      caloriesPer100 = decode(Calories.from(caloriesPer100)),
      proteinPer100 = decode(Protein.from(proteinPer100)),
      fatPer100 = decode(Fat.from(fatPer100)),
      carbsPer100 = decode(Carbs.from(carbsPer100))
    )

  def dishWithIngredients(
      dishId: Long,
      name: String,
      weight: Int,
      ingredients: Vector[IngredientWithProduct] = Vector.empty
  ): DishWithIngredients =
    DishWithIngredients(
      dish = Dish(
        id = decode(DishId.from(dishId)),
        userId = userId,
        name = decode(DishName.from(name)),
        cookedWeightGrams = decode(Weight.from(weight)),
        createdAt = now,
        updatedAt = now
      ),
      ingredients = ingredients
    )

/** In-memory [[ProductRepository]] with per-test behavior overridable through function fields. */
object MockProductRepository:
  type Create =
    (UserId, ProductName, Option[CategoryName], ProductUnit, Calories, Protein, Fat, Carbs) => Task[Product]
  type Update =
    (UserId, ProductId, ProductName, Option[CategoryName], ProductUnit, Calories, Protein, Fat, Carbs) => Task[
      Option[Product]
    ]

final case class MockProductRepository(
    listF: (UserId, Option[String], Int, Int) => Task[Vector[Product]] = (_, _, _, _) => ZIO.succeed(Vector.empty),
    categoriesF: UserId => Task[Vector[String]] = _ => ZIO.succeed(Vector.empty),
    findActiveF: (UserId, ProductId) => Task[Option[Product]] = (_, _) => ZIO.succeed(None),
    findExistingIdsF: (UserId, Vector[ProductId]) => Task[Vector[ProductId]] = (_, ids) => ZIO.succeed(ids),
    existingKeysF: (UserId, Vector[String]) => Task[Set[(String, Option[String])]] = (_, _) => ZIO.succeed(Set.empty),
    createBatchF: (UserId, Vector[NewProductRow]) => Task[Int] = (_, _) =>
      ZIO.die(IllegalStateException("createBatchF not configured")),
    createF: MockProductRepository.Create = (_, _, _, _, _, _, _, _) =>
      ZIO.die(IllegalStateException("createF not configured")),
    updateF: MockProductRepository.Update = (_, _, _, _, _, _, _, _, _) => ZIO.succeed(None),
    archiveF: (UserId, ProductId) => Task[Boolean] = (_, _) => ZIO.succeed(false)
) extends ProductRepository:

  override def list(userId: UserId, query: Option[String], limit: Int, offset: Int): Task[Vector[Product]] =
    listF(userId, query, limit, offset)

  override def categories(userId: UserId): Task[Vector[String]] = categoriesF(userId)

  override def findActive(userId: UserId, productId: ProductId): Task[Option[Product]] =
    findActiveF(userId, productId)

  override def findExistingIds(userId: UserId, ids: Vector[ProductId]): Task[Vector[ProductId]] =
    findExistingIdsF(userId, ids)

  override def existingKeys(userId: UserId, names: Vector[String]): Task[Set[(String, Option[String])]] =
    existingKeysF(userId, names)

  override def createBatch(userId: UserId, rows: Vector[NewProductRow]): Task[Int] =
    createBatchF(userId, rows)

  override def create(
      userId: UserId,
      name: ProductName,
      category: Option[CategoryName],
      unit: ProductUnit,
      caloriesPer100: Calories,
      proteinPer100: Protein,
      fatPer100: Fat,
      carbsPer100: Carbs
  ): Task[Product] =
    createF(userId, name, category, unit, caloriesPer100, proteinPer100, fatPer100, carbsPer100)

  override def update(
      userId: UserId,
      productId: ProductId,
      name: ProductName,
      category: Option[CategoryName],
      unit: ProductUnit,
      caloriesPer100: Calories,
      proteinPer100: Protein,
      fatPer100: Fat,
      carbsPer100: Carbs
  ): Task[Option[Product]] =
    updateF(userId, productId, name, category, unit, caloriesPer100, proteinPer100, fatPer100, carbsPer100)

  override def archive(userId: UserId, productId: ProductId): Task[Boolean] = archiveF(userId, productId)

/** In-memory [[DishRepository]] with per-test behavior overridable through function fields. */
final case class MockDishRepository(
    listF: (UserId, Option[String], Int, Int) => Task[Vector[DishWithIngredients]] = (_, _, _, _) =>
      ZIO.succeed(Vector.empty),
    getF: (UserId, DishId) => Task[Option[DishWithIngredients]] = (_, _) => ZIO.succeed(None),
    createF: (UserId, DishName, Weight, Vector[NewDishIngredient]) => Task[DishWithIngredients] = (_, _, _, _) =>
      ZIO.die(IllegalStateException("createF not configured")),
    updateF: (UserId, DishId, DishName, Weight, Vector[NewDishIngredient]) => Task[Option[DishWithIngredients]] =
      (_, _, _, _, _) => ZIO.succeed(None),
    deleteF: (UserId, DishId) => Task[Boolean] = (_, _) => ZIO.succeed(false)
) extends DishRepository:

  override def listWithIngredients(
      userId: UserId,
      query: Option[String],
      limit: Int,
      offset: Int): Task[Vector[DishWithIngredients]] =
    listF(userId, query, limit, offset)

  override def getWithIngredients(userId: UserId, dishId: DishId): Task[Option[DishWithIngredients]] =
    getF(userId, dishId)

  override def create(
      userId: UserId,
      name: DishName,
      cookedWeightGrams: Weight,
      ingredients: Vector[NewDishIngredient]
  ): Task[DishWithIngredients] =
    createF(userId, name, cookedWeightGrams, ingredients)

  override def update(
      userId: UserId,
      dishId: DishId,
      name: DishName,
      cookedWeightGrams: Weight,
      ingredients: Vector[NewDishIngredient]
  ): Task[Option[DishWithIngredients]] =
    updateF(userId, dishId, name, cookedWeightGrams, ingredients)

  override def delete(userId: UserId, dishId: DishId): Task[Boolean] = deleteF(userId, dishId)
