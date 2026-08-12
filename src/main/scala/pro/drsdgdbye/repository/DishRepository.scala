package pro.drsdgdbye.repository

import io.getquill.*
import pro.drsdgdbye.db.QuillContext
import pro.drsdgdbye.domain.*
import zio.*

import java.time.Instant

/** Flat row mirroring the `dishes` table, with raw (non-validated) column types. */
final case class DishRow(
    id: Long,
    userId: Long,
    name: String,
    cookedWeightGrams: Int,
    createdAt: Instant,
    updatedAt: Instant
)

/** Quill schema and insert mappings for [[DishRow]]. */
object DishRow:
  inline given SchemaMeta[DishRow] = schemaMeta[DishRow]("dishes")
  inline given InsertMeta[DishRow] = insertMeta[DishRow](_.id, _.createdAt, _.updatedAt)

/** Flat row mirroring the `dish_ingredients` table. */
final case class DishIngredientRow(
    id: Long,
    dishId: Long,
    productId: Long,
    quantity: Int,
    position: Int
)

/** Quill schema and insert mappings for [[DishIngredientRow]]. */
object DishIngredientRow:
  inline given SchemaMeta[DishIngredientRow] = schemaMeta[DishIngredientRow]("dish_ingredients")
  inline given InsertMeta[DishIngredientRow] = insertMeta[DishIngredientRow](_.id)

/** Join result of a dish ingredient with its product, carrying product nutrition. */
final case class DishIngredientWithProductRow(
    id: Long,
    dishId: Long,
    productId: Long,
    quantity: Int,
    position: Int,
    productName: String,
    productUnit: String,
    caloriesPer100: Int,
    proteinPer100: Int,
    fatPer100: Int,
    carbsPer100: Int
)

/** Domain-typed ingredient enriched with its product data. */
final case class IngredientWithProduct(
    productId: ProductId,
    productName: ProductName,
    unit: ProductUnit,
    quantity: Quantity,
    position: Position,
    caloriesPer100: Calories,
    proteinPer100: Protein,
    fatPer100: Fat,
    carbsPer100: Carbs
)

/** A dish together with its domain-typed ingredients. */
final case class DishWithIngredients(
    dish: Dish,
    ingredients: Vector[IngredientWithProduct]
)

/** Ingredient to be persisted for a dish, with its ordering position already assigned. */
final case class NewDishIngredient(
    productId: ProductId,
    quantity: Quantity,
    position: Position
)

/** Data access for dishes and their ingredients; the only layer allowed to talk to Quill for dishes. */
trait DishRepository:
  def listWithIngredients(
      userId: UserId,
      query: Option[String],
      limit: Int,
      offset: Int): Task[Vector[DishWithIngredients]]

  def getWithIngredients(userId: UserId, dishId: DishId): Task[Option[DishWithIngredients]]

  def create(
      userId: UserId,
      name: DishName,
      cookedWeightGrams: Weight,
      ingredients: Vector[NewDishIngredient]
  ): Task[DishWithIngredients]

  def update(
      userId: UserId,
      dishId: DishId,
      name: DishName,
      cookedWeightGrams: Weight,
      ingredients: Vector[NewDishIngredient]
  ): Task[Option[DishWithIngredients]]

  def delete(userId: UserId, dishId: DishId): Task[Boolean]

/** ZLayer wiring for the live [[DishRepository]] implementation. */
object DishRepositoryLive:
  val layer: ZLayer[QuillContext.Ctx, Nothing, DishRepository] =
    ZLayer.fromFunction(DishRepositoryLive(_))

final case class DishRepositoryLive(ctx: QuillContext.Ctx) extends DishRepository:
  import ctx.*

  private def decode[A](e: Either[DomainError, A]): A =
    e.fold(_ => throw IllegalStateException("Database data violates domain invariants"), identity)

  /** Escapes LIKE wildcards (\, %, _) so the query is matched literally, then appends `%` for a prefix search.
    */
  private def likePattern(query: String): String =
    query.flatMap {
      case '\\' => "\\\\"
      case '%' => "\\%"
      case '_' => "\\_"
      case c => c.toString
    } + "%"

  private def rowToDish(r: DishRow): Dish =
    Dish(
      id = decode(DishId.from(r.id)),
      userId = decode(UserId.from(r.userId)),
      name = decode(DishName.from(r.name)),
      cookedWeightGrams = decode(Weight.from(r.cookedWeightGrams)),
      createdAt = r.createdAt,
      updatedAt = r.updatedAt
    )

  private def rowToIngredient(r: DishIngredientWithProductRow): IngredientWithProduct =
    IngredientWithProduct(
      productId = decode(ProductId.from(r.productId)),
      productName = decode(ProductName.from(r.productName)),
      unit = ProductUnit.valueOf(r.productUnit),
      quantity = decode(Quantity.from(r.quantity)),
      position = decode(Position.from(r.position)),
      caloriesPer100 = decode(Calories.from(r.caloriesPer100)),
      proteinPer100 = decode(Protein.from(r.proteinPer100)),
      fatPer100 = decode(Fat.from(r.fatPer100)),
      carbsPer100 = decode(Carbs.from(r.carbsPer100))
    )

  private def ingredientRows(dishId: Long, ingredients: Vector[NewDishIngredient]): List[DishIngredientRow] =
    ingredients
      .map(i => DishIngredientRow(0L, dishId, i.productId.value, i.quantity.value, i.position.value))
      .toList

  /** Loads all ingredients of the given dishes joined with their products, ordered by position.
    */
  private def fetchIngredients(dishIds: List[Long]): Task[Vector[DishIngredientWithProductRow]] =
    ctx
      .run {
        query[DishIngredientRow]
          .join(query[ProductRow])
          .on((di, p) => di.productId == p.id)
          .filter((di, _) => liftQuery(dishIds).contains(di.dishId))
          .sortBy((di, _) => di.position)(using Ord.asc)
          .map((di, p) =>
            DishIngredientWithProductRow(
              di.id,
              di.dishId,
              di.productId,
              di.quantity,
              di.position,
              p.name,
              p.unit,
              p.caloriesPer100,
              p.proteinPer100,
              p.fatPer100,
              p.carbsPer100
            )
          )
      }
      .map(_.toVector)

  /** Groups the fetched ingredient rows by dish id and assembles [[DishWithIngredients]] values, one per input dish.
    */
  private def build(
      dishes: Vector[DishRow],
      ingredients: Vector[DishIngredientWithProductRow]): Vector[DishWithIngredients] =
    val grouped = ingredients.groupBy(_.dishId)
    dishes.map { d =>
      val ings = grouped.getOrElse(d.id, Vector.empty).map(rowToIngredient)
      DishWithIngredients(rowToDish(d), ings)
    }

  def listWithIngredients(
      userId: UserId,
      q: Option[String],
      limit: Int,
      offset: Int): Task[Vector[DishWithIngredients]] =
    val pattern = q.map(v => likePattern(v.toLowerCase)).getOrElse("%")
    for
      dishes <- ctx.run {
        query[DishRow]
          .filter(d => d.userId == lift(userId.value) && (d.name.toLowerCase like lift(pattern)))
          .sortBy(d => d.name.toLowerCase)(using Ord.asc)
          .drop(lift(offset))
          .take(lift(limit))
      }
      ingredients <-
        if dishes.isEmpty then ZIO.succeed(Vector.empty[DishIngredientWithProductRow])
        else fetchIngredients(dishes.map(_.id).toList)
    yield build(dishes.toVector, ingredients)

  def getWithIngredients(userId: UserId, dishId: DishId): Task[Option[DishWithIngredients]] =
    for
      dish <- ctx
        .run {
          query[DishRow].filter(d => d.userId == lift(userId.value) && d.id == lift(dishId.value))
        }
        .map(_.headOption)
      ingredients <-
        dish match
          case None => ZIO.succeed(Vector.empty[DishIngredientWithProductRow])
          case Some(d) => fetchIngredients(List(d.id))
    yield dish.map(d => DishWithIngredients(rowToDish(d), ingredients.map(rowToIngredient)))

  /** Inserts the dish and its ingredients in a single transaction, then returns the created dish with ingredients.
    */
  def create(
      userId: UserId,
      name: DishName,
      cookedWeightGrams: Weight,
      ingredients: Vector[NewDishIngredient]
  ): Task[DishWithIngredients] =
    val dishIdEffect = ctx.transaction {
      for
        dishId <- ctx.run {
          query[DishRow]
            .insert(
              _.userId -> lift(userId.value),
              _.name -> lift(name.value),
              _.cookedWeightGrams -> lift(cookedWeightGrams.value),
              _.createdAt -> infix"now()".as[Instant],
              _.updatedAt -> infix"now()".as[Instant]
            )
            .returningGenerated(_.id)
        }
        _ <- ctx.run {
          liftQuery(ingredientRows(dishId, ingredients)).foreach(r => query[DishIngredientRow].insertValue(r))
        }
      yield dishId
    }
    for
      id <- dishIdEffect
      result <- getWithIngredients(userId, decode(DishId.from(id)))
      dish <- ZIO.fromOption(result).orElseFail(IllegalStateException("Dish insert returned no row"))
    yield dish

  /** Replaces the dish fields and its full ingredient set atomically: locks the dish row, updates the dish, deletes the
    * old ingredients and inserts the new ones.
    */
  def update(
      userId: UserId,
      dishId: DishId,
      name: DishName,
      cookedWeightGrams: Weight,
      ingredients: Vector[NewDishIngredient]
  ): Task[Option[DishWithIngredients]] =
    val updateEffect = ctx.transaction {
      for
        locked <- ctx
          .run(
            sql"SELECT * FROM dishes WHERE user_id = ${lift(userId.value)} AND id = ${lift(dishId.value)} FOR UPDATE"
              .as[Query[DishRow]]
          )
          .map(_.headOption)
        result <-
          locked match
            case None => ZIO.succeed(None)
            case Some(_) =>
              ctx.run {
                query[DishRow]
                  .filter(d => d.id == lift(dishId.value))
                  .update(
                    _.name -> lift(name.value),
                    _.cookedWeightGrams -> lift(cookedWeightGrams.value),
                    _.updatedAt -> infix"now()".as[Instant]
                  )
              } *>
                ctx.run(query[DishIngredientRow].filter(di => di.dishId == lift(dishId.value)).delete) *>
                ctx.run {
                  liftQuery(ingredientRows(dishId.value, ingredients))
                    .foreach(r => query[DishIngredientRow].insertValue(r))
                } *>
                ZIO.succeed(Some(()))
      yield result
    }
    updateEffect.flatMap {
      case None => ZIO.succeed(None)
      case Some(_) => getWithIngredients(userId, dishId)
    }

  def delete(userId: UserId, dishId: DishId): Task[Boolean] =
    ctx
      .run {
        query[DishRow].filter(d => d.userId == lift(userId.value) && d.id == lift(dishId.value)).delete
      }
      .map(_ > 0)
