package pro.drsdgdbye.service

import pro.drsdgdbye.domain.*
import pro.drsdgdbye.repository.ProductRepository
import zio.*

/** API payload for creating or updating a product. */
final case class ProductInput(
    name: String,
    category: Option[String],
    unit: ProductUnit,
    caloriesPer100: Int,
    proteinPer100: Int,
    fatPer100: Int,
    carbsPer100: Int
)

/** Use-case operations over products, hiding the repository behind domain-typed boundaries. */
trait ProductService:
  def list(userId: UserId, query: Option[String], limit: Int, offset: Int): IO[DomainError, Vector[Product]]

  def categories(userId: UserId): IO[DomainError, Vector[String]]

  def create(userId: UserId, input: ProductInput): IO[DomainError, Product]

  def update(userId: UserId, productId: Long, input: ProductInput): IO[DomainError, Product]

  def archive(userId: UserId, productId: Long): IO[DomainError, Unit]

/** ZLayer wiring for the live [[ProductService]] implementation. */
object ProductServiceLive:
  val layer: ZLayer[ProductRepository, Nothing, ProductService] =
    ZLayer.fromFunction(ProductServiceLive(_))

final case class ProductServiceLive(repo: ProductRepository) extends ProductService:
  private def repoCall[A](zio: Task[A]): IO[DomainError, A] =
    zio
      .tapErrorCause(cause => ZIO.logErrorCause("Repository call failed", cause))
      .mapError(_ => DomainError.InternalError)

  private def validate[A](e: Either[DomainError, A]): IO[DomainError, A] =
    ZIO.fromEither(e)

  private def validateOption[A](o: Option[String])(f: String => Either[DomainError, A]): IO[DomainError, Option[A]] =
    o match
      case Some(s) => validate(f(s)).map(Some(_))
      case None => ZIO.succeed(None)

  def list(userId: UserId, query: Option[String], limit: Int, offset: Int): IO[DomainError, Vector[Product]] =
    repoCall(repo.list(userId, query.map(_.trim).filter(_.nonEmpty), limit, offset))

  def categories(userId: UserId): IO[DomainError, Vector[String]] =
    repoCall(repo.categories(userId))

  def create(userId: UserId, input: ProductInput): IO[DomainError, Product] =
    for
      name <- validate(ProductName.from(input.name))
      category <- validateOption(input.category)(CategoryName.from)
      calories <- validate(Calories.from(input.caloriesPer100))
      protein <- validate(Protein.from(input.proteinPer100))
      fat <- validate(Fat.from(input.fatPer100))
      carbs <- validate(Carbs.from(input.carbsPer100))
      product <- repoCall(repo.create(userId, name, category, input.unit, calories, protein, fat, carbs))
    yield product

  def update(userId: UserId, productId: Long, input: ProductInput): IO[DomainError, Product] =
    for
      id <- validate(ProductId.from(productId))
      name <- validate(ProductName.from(input.name))
      category <- validateOption(input.category)(CategoryName.from)
      calories <- validate(Calories.from(input.caloriesPer100))
      protein <- validate(Protein.from(input.proteinPer100))
      fat <- validate(Fat.from(input.fatPer100))
      carbs <- validate(Carbs.from(input.carbsPer100))
      updated <- repoCall(repo.update(userId, id, name, category, input.unit, calories, protein, fat, carbs))
      product <- ZIO.fromOption(updated).mapError(_ => DomainError.ProductNotFound)
    yield product

  def archive(userId: UserId, productId: Long): IO[DomainError, Unit] =
    for
      id <- validate(ProductId.from(productId))
      archived <- repoCall(repo.archive(userId, id))
      _ <- if archived then ZIO.unit else ZIO.fail(DomainError.ProductNotFound)
    yield ()
