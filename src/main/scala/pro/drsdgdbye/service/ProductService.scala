package pro.drsdgdbye.service

import pro.drsdgdbye.domain.*
import pro.drsdgdbye.repository.{NewProductRow, ProductRepository}
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

/** Reason a single import record was rejected. */
enum ImportIssueCode:
  case InvalidRecord
  case InvalidUnit
  case InvalidName
  case InvalidCategory
  case InvalidCalories
  case InvalidProtein
  case InvalidFat
  case InvalidCarbs
  case Duplicate

  /** Stable snake_case wire value as documented in the API contract. */
  def wire: String = this match
    case InvalidRecord => "invalid_record"
    case InvalidUnit => "invalid_unit"
    case InvalidName => "invalid_name"
    case InvalidCategory => "invalid_category"
    case InvalidCalories => "invalid_calories"
    case InvalidProtein => "invalid_protein"
    case InvalidFat => "invalid_fat"
    case InvalidCarbs => "invalid_carbs"
    case Duplicate => "duplicate"

/** One rejected import record, by its position in the request body. */
final case class ImportItemError(index: Int, code: ImportIssueCode)

/** Outcome of one import batch: inserted records and per-record rejections. */
final case class ProductImportResult(imported: Int, errors: Vector[ImportItemError])

/** Per-item input of an import batch: a parsed payload or a structural failure detected by the API layer. */
type ImportItem = Either[ImportIssueCode, ProductInput]

/** Use-case operations over products, hiding the repository behind domain-typed boundaries. */
trait ProductService:
  def list(userId: UserId, query: Option[String], limit: Int, offset: Int): IO[DomainError, Vector[Product]]

  def categories(userId: UserId): IO[DomainError, Vector[String]]

  def create(userId: UserId, input: ProductInput): IO[DomainError, Product]

  def update(userId: UserId, productId: Long, input: ProductInput): IO[DomainError, Product]

  def archive(userId: UserId, productId: Long): IO[DomainError, Unit]

  def importProducts(userId: UserId, items: Vector[ImportItem]): IO[DomainError, ProductImportResult]

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

  /** Batch-import products: validates each record on its own, skips duplicates by the case-sensitive (name, category)
    * key (active products in the catalog or records already seen in this batch), then inserts the rest in a single
    * multi-row insert.
    */
  def importProducts(userId: UserId, items: Vector[ImportItem]): IO[DomainError, ProductImportResult] =
    if items.isEmpty || items.size > Constants.MaxImportBatchSize then ZIO.fail(DomainError.ValidationError)
    else
      val evaluated: Vector[Either[(Int, ImportIssueCode), (Int, ValidImportProduct)]] =
        items.zipWithIndex.map { (item, idx) =>
          item match
            case Left(code) => Left((idx, code))
            case Right(input) =>
              validateProductInput(input) match
                case Left(code) => Left((idx, code))
                case Right(product) => Right(idx -> product)
        }
      val names = evaluated.collect { case Right((_, product)) => product.name.value }.distinct
      for
        existing <- repoCall(repo.existingKeys(userId, names))
        result <- insertValid(userId, evaluated, existing)
      yield result

  private final case class ValidImportProduct(
      name: ProductName,
      category: Option[CategoryName],
      unit: ProductUnit,
      caloriesPer100: Calories,
      proteinPer100: Protein,
      fatPer100: Fat,
      carbsPer100: Carbs
  )

  /** Validates every field of a product payload through the domain smart constructors, reporting the first failing
    * field as a granular issue code.
    */
  private def validateProductInput(input: ProductInput): Either[ImportIssueCode, ValidImportProduct] =
    (ProductName.from(input.name), input.category.map(CategoryName.from)) match
      case (Left(_), _) => Left(ImportIssueCode.InvalidName)
      case (_, Some(Left(_))) => Left(ImportIssueCode.InvalidCategory)
      case (Right(name), category) =>
        (
          Calories.from(input.caloriesPer100),
          Protein.from(input.proteinPer100),
          Fat.from(input.fatPer100),
          Carbs.from(
            input.carbsPer100
          )
        ) match
          case (Left(_), _, _, _) => Left(ImportIssueCode.InvalidCalories)
          case (_, Left(_), _, _) => Left(ImportIssueCode.InvalidProtein)
          case (_, _, Left(_), _) => Left(ImportIssueCode.InvalidFat)
          case (_, _, _, Left(_)) => Left(ImportIssueCode.InvalidCarbs)
          case (Right(calories), Right(protein), Right(fat), Right(carbs)) =>
            Right(
              ValidImportProduct(
                name,
                category.flatMap(_.toOption),
                input.unit,
                calories,
                protein,
                fat,
                carbs
              )
            )

  /** Deduplicates the evaluated records against the catalog (active products) and within the batch, then inserts the
    * remaining records.
    */
  private def insertValid(
      userId: UserId,
      evaluated: Vector[Either[(Int, ImportIssueCode), (Int, ValidImportProduct)]],
      existingKeys: Set[(String, Option[String])]
  ): IO[DomainError, ProductImportResult] =
    val (errors, toInsert, _) = evaluated.foldLeft(
      (Vector.empty[ImportItemError], Vector.empty[(Int, ValidImportProduct)], existingKeys)
    ) { case ((errs, acc, seen), item) =>
      item match
        case Left((idx, code)) => (errs :+ ImportItemError(idx, code), acc, seen)
        case Right((idx, product)) =>
          val key = (product.name.value, product.category.map(_.value))
          if seen.contains(key) then (errs :+ ImportItemError(idx, ImportIssueCode.Duplicate), acc, seen)
          else (errs, acc :+ (idx -> product), seen + key)
    }
    if toInsert.isEmpty then ZIO.succeed(ProductImportResult(0, errors))
    else
      for imported <- repoCall(
          repo.createBatch(
            userId,
            toInsert.map { case (_, product) =>
              NewProductRow(
                name = product.name.value,
                category = product.category.map(_.value),
                unit = product.unit.toString,
                caloriesPer100 = product.caloriesPer100.value,
                proteinPer100 = product.proteinPer100.value,
                fatPer100 = product.fatPer100.value,
                carbsPer100 = product.carbsPer100.value
              )
            }
          )
        )
      yield ProductImportResult(imported, errors)
