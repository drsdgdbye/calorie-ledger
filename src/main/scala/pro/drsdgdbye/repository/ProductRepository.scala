package pro.drsdgdbye.repository

import io.getquill.*
import pro.drsdgdbye.db.QuillContext
import pro.drsdgdbye.domain.*
import zio.*

import java.time.Instant

/** Flat row mirroring the `products` table, with raw (non-validated) column types. */
final case class ProductRow(
    id: Long,
    userId: Long,
    name: String,
    category: Option[String],
    unit: String,
    caloriesPer100: Int,
    proteinPer100: Int,
    fatPer100: Int,
    carbsPer100: Int,
    isArchived: Boolean,
    createdAt: Instant,
    updatedAt: Instant
)

/** Quill schema and insert mappings for [[ProductRow]]. */
object ProductRow:
  inline given SchemaMeta[ProductRow] = schemaMeta[ProductRow]("products")
  inline given InsertMeta[ProductRow] = insertMeta[ProductRow](_.id, _.createdAt, _.updatedAt, _.isArchived)

/** Flat import row for a multi-row insert; defaults (id, is_archived, created_at, updated_at) are left to the database.
  */
final case class NewProductRow(
    name: String,
    category: Option[String],
    unit: String,
    caloriesPer100: Int,
    proteinPer100: Int,
    fatPer100: Int,
    carbsPer100: Int
)

/** Data access for products; the only layer allowed to talk to Quill for products. */
trait ProductRepository:
  def list(userId: UserId, query: Option[String], limit: Int, offset: Int): Task[Vector[Product]]

  def categories(userId: UserId): Task[Vector[String]]

  def findActive(userId: UserId, productId: ProductId): Task[Option[Product]]

  def findExistingIds(userId: UserId, ids: Vector[ProductId]): Task[Vector[ProductId]]

  /** Returns the (name, category) pairs of active products for the user whose name is among `names`, compared exactly
    * (case-sensitive, as stored).
    */
  def existingKeys(userId: UserId, names: Vector[String]): Task[Set[(String, Option[String])]]

  def createBatch(userId: UserId, rows: Vector[NewProductRow]): Task[Int]

  def create(
      userId: UserId,
      name: ProductName,
      category: Option[CategoryName],
      unit: ProductUnit,
      caloriesPer100: Calories,
      proteinPer100: Protein,
      fatPer100: Fat,
      carbsPer100: Carbs
  ): Task[Product]

  def update(
      userId: UserId,
      productId: ProductId,
      name: ProductName,
      category: Option[CategoryName],
      unit: ProductUnit,
      caloriesPer100: Calories,
      proteinPer100: Protein,
      fatPer100: Fat,
      carbsPer100: Carbs
  ): Task[Option[Product]]

  def archive(userId: UserId, productId: ProductId): Task[Boolean]

/** ZLayer wiring for the live [[ProductRepository]] implementation. */
object ProductRepositoryLive:
  val layer: ZLayer[QuillContext.Ctx, Nothing, ProductRepository] =
    ZLayer.fromFunction(ProductRepositoryLive(_))

final case class ProductRepositoryLive(ctx: QuillContext.Ctx) extends ProductRepository:
  import ctx.*

  private def decode[A](e: Either[DomainError, A]): A =
    e.fold(_ => throw IllegalStateException("Database data violates domain invariants"), identity)

  private def rowToProduct(r: ProductRow): Product =
    Product(
      id = decode(ProductId.from(r.id)),
      userId = decode(UserId.from(r.userId)),
      name = decode(ProductName.from(r.name)),
      category = r.category.map(c => decode(CategoryName.from(c))),
      unit = ProductUnit.valueOf(r.unit),
      caloriesPer100 = decode(Calories.from(r.caloriesPer100)),
      proteinPer100 = decode(Protein.from(r.proteinPer100)),
      fatPer100 = decode(Fat.from(r.fatPer100)),
      carbsPer100 = decode(Carbs.from(r.carbsPer100)),
      isArchived = r.isArchived,
      createdAt = r.createdAt,
      updatedAt = r.updatedAt
    )

  /** Escapes LIKE wildcards (\, %, _) so the query is matched literally, then appends `%` for a prefix search.
    */
  private def likePattern(query: String): String =
    query.flatMap {
      case '\\' => "\\\\"
      case '%' => "\\%"
      case '_' => "\\_"
      case c => c.toString
    } + "%"

  def list(userId: UserId, q: Option[String], limit: Int, offset: Int): Task[Vector[Product]] =
    val pattern = q.map(v => likePattern(v.toLowerCase)).getOrElse("%")
    ctx
      .run {
        query[ProductRow]
          .filter(p => p.userId == lift(userId.value) && !p.isArchived && (p.name.toLowerCase like lift(pattern)))
          .sortBy(p => p.name.toLowerCase)(using Ord.asc)
          .drop(lift(offset))
          .take(lift(limit))
      }
      .map(_.map(rowToProduct).toVector)

  def categories(userId: UserId): Task[Vector[String]] =
    ctx
      .run {
        query[ProductRow]
          .filter(p => p.userId == lift(userId.value) && !p.isArchived)
          .map(_.category)
          .distinct
      }
      .map(_.flatten.sorted.toVector)

  def findActive(userId: UserId, productId: ProductId): Task[Option[Product]] =
    ctx
      .run {
        query[ProductRow]
          .filter(p => p.userId == lift(userId.value) && p.id == lift(productId.value) && !p.isArchived)
      }
      .map(_.headOption.map(rowToProduct))

  def findExistingIds(userId: UserId, ids: Vector[ProductId]): Task[Vector[ProductId]] =
    if ids.isEmpty then ZIO.succeed(Vector.empty)
    else
      ctx
        .run {
          query[ProductRow]
            .filter(p => p.userId == lift(userId.value) && liftQuery(ids.map(_.value).toList).contains(p.id))
            .map(_.id)
        }
        .map(_.map(id => decode(ProductId.from(id))).toVector)

  def existingKeys(userId: UserId, names: Vector[String]): Task[Set[(String, Option[String])]] =
    if names.isEmpty then ZIO.succeed(Set.empty)
    else
      ctx
        .run {
          query[ProductRow]
            .filter(p => p.userId == lift(userId.value) && !p.isArchived && liftQuery(names.toList).contains(p.name))
            .map(p => (p.name, p.category))
        }
        .map(_.toSet)

  def createBatch(userId: UserId, rows: Vector[NewProductRow]): Task[Int] =
    if rows.isEmpty then ZIO.succeed(0)
    else
      ctx
        .run {
          liftQuery(rows.toList).foreach { row =>
            query[ProductRow].insert(
              _.userId -> lift(userId.value),
              _.name -> row.name,
              _.category -> row.category,
              _.unit -> infix"CAST(${row.unit} AS product_unit)".as[String],
              _.caloriesPer100 -> row.caloriesPer100,
              _.proteinPer100 -> row.proteinPer100,
              _.fatPer100 -> row.fatPer100,
              _.carbsPer100 -> row.carbsPer100
            )
          }
        }
        .map(_.sum.toInt)

  def create(
      userId: UserId,
      name: ProductName,
      category: Option[CategoryName],
      unit: ProductUnit,
      caloriesPer100: Calories,
      proteinPer100: Protein,
      fatPer100: Fat,
      carbsPer100: Carbs
  ): Task[Product] =
    for
      id <- ctx.run {
        query[ProductRow]
          .insert(
            _.userId -> lift(userId.value),
            _.name -> lift(name.value),
            _.category -> lift(category.map(_.value)),
            _.unit -> infix"CAST(${lift(unit.toString)} AS product_unit)".as[String],
            _.caloriesPer100 -> lift(caloriesPer100.value),
            _.proteinPer100 -> lift(proteinPer100.value),
            _.fatPer100 -> lift(fatPer100.value),
            _.carbsPer100 -> lift(carbsPer100.value),
            _.isArchived -> lift(false),
            _.createdAt -> infix"now()".as[Instant],
            _.updatedAt -> infix"now()".as[Instant]
          )
          .returningGenerated(_.id)
      }
      product <- findActive(userId, decode(ProductId.from(id)))
      result <- ZIO.fromOption(product).orElseFail(IllegalStateException("Product insert returned no row"))
    yield result

  def update(
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
    ctx
      .run {
        query[ProductRow]
          .filter(p => p.userId == lift(userId.value) && p.id == lift(productId.value) && !p.isArchived)
          .update(
            _.name -> lift(name.value),
            _.category -> lift(category.map(_.value)),
            _.unit -> infix"CAST(${lift(unit.toString)} AS product_unit)".as[String],
            _.caloriesPer100 -> lift(caloriesPer100.value),
            _.proteinPer100 -> lift(proteinPer100.value),
            _.fatPer100 -> lift(fatPer100.value),
            _.carbsPer100 -> lift(carbsPer100.value),
            _.updatedAt -> infix"now()".as[Instant]
          )
      }
      .flatMap { updated =>
        if updated == 0 then ZIO.succeed(None)
        else findActive(userId, productId)
      }

  def archive(userId: UserId, productId: ProductId): Task[Boolean] =
    ctx
      .run {
        query[ProductRow]
          .filter(p => p.userId == lift(userId.value) && p.id == lift(productId.value) && !p.isArchived)
          .update(_.isArchived -> true, _.updatedAt -> infix"now()".as[Instant])
      }
      .map(_ > 0)
