package pro.drsdgdbye.api

import pro.drsdgdbye.domain.{Constants, DomainError, Product, ProductUnit, UserId}
import pro.drsdgdbye.service.{
  DishDetailView,
  DishIngredientInput,
  DishIngredientView,
  DishInput,
  DishListItemView,
  DishService,
  NutritionView,
  ProductInput,
  ProductService
}
import sttp.capabilities.WebSockets
import sttp.capabilities.zio.ZioStreams
import sttp.model.StatusCode
import sttp.tapir.*
import sttp.tapir.generic.auto.*
import sttp.tapir.json.zio.*
import sttp.tapir.ztapir.{endpoint as _, path as _, query as _, statusCode as _, *}
import zio.*
import zio.json.*

/** Uniform error body returned for any failed request. */
final case class ErrorResponse(error: String)

/** Health check response body. */
final case class Health(status: String)

/** Wire representation of a product with plain (non-domain) types. */
final case class ProductResponse(
    id: Long,
    name: String,
    category: Option[String],
    unit: ProductUnit,
    caloriesPer100: Int,
    proteinPer100: Int,
    fatPer100: Int,
    carbsPer100: Int,
    isArchived: Boolean
)

object ProductResponse:
  /** Converts a domain [[pro.drsdgdbye.domain.Product]] into its wire representation. */
  def from(p: Product): ProductResponse =
    ProductResponse(
      id = p.id.value,
      name = p.name.value,
      category = p.category.map(_.value),
      unit = p.unit,
      caloriesPer100 = p.caloriesPer100.value,
      proteinPer100 = p.proteinPer100.value,
      fatPer100 = p.fatPer100.value,
      carbsPer100 = p.carbsPer100.value,
      isArchived = p.isArchived
    )

/** Paginated product list response. */
final case class ProductListResponse(items: Vector[ProductResponse])

/** Paginated dish list response. */
final case class DishListResponse(items: Vector[DishListItemView])

/** Json codecs for all API wire types; kept in one place as the serialization SSOT. */
object ApiCodecs:
  given JsonCodec[ProductUnit] =
    JsonCodec.string.transformOrFail(s => ProductUnit.values.find(_.toString == s).toRight("invalid unit"), _.toString)
  given JsonCodec[ErrorResponse] = DeriveJsonCodec.gen[ErrorResponse]
  given JsonCodec[Health] = DeriveJsonCodec.gen[Health]
  given JsonCodec[ProductInput] = DeriveJsonCodec.gen[ProductInput]
  given JsonCodec[ProductResponse] = DeriveJsonCodec.gen[ProductResponse]
  given JsonCodec[ProductListResponse] = DeriveJsonCodec.gen[ProductListResponse]
  given JsonCodec[DishIngredientInput] = DeriveJsonCodec.gen[DishIngredientInput]
  given JsonCodec[DishInput] = DeriveJsonCodec.gen[DishInput]
  given JsonCodec[DishIngredientView] = DeriveJsonCodec.gen[DishIngredientView]
  given JsonCodec[NutritionView] = DeriveJsonCodec.gen[NutritionView]
  given JsonCodec[DishListResponse] = DeriveJsonCodec.gen[DishListResponse]
  given JsonCodec[DishListItemView] = DeriveJsonCodec.gen[DishListItemView]
  given JsonCodec[DishDetailView] = DeriveJsonCodec.gen[DishDetailView]

/** Maps [[pro.drsdgdbye.domain.DomainError]] to HTTP status and an English error body. */
object ApiErrors:
  def toHttp(e: DomainError): (StatusCode, ErrorResponse) =
    e match
      case DomainError.ProductNotFound => StatusCode.NotFound -> ErrorResponse("Product not found")
      case DomainError.DishNotFound => StatusCode.NotFound -> ErrorResponse("Dish not found")
      case DomainError.ValidationError => StatusCode.BadRequest -> ErrorResponse("Invalid data")
      case DomainError.InternalError => StatusCode.InternalServerError -> ErrorResponse("Internal server error")

/** Common pagination parsing with a default limit and a hard max cap. */
object Pagination:
  final case class Page(limit: Int, offset: Int)

  def parse(limitParam: Option[String], offsetParam: Option[String]): Either[DomainError, Page] =
    for
      limit <- parseLimit(limitParam)
      offset <- parseOffset(offsetParam)
    yield Page(limit, offset)

  private def parseLimit(s: Option[String]): Either[DomainError, Int] =
    s match
      case None => Right(Constants.DefaultPageLimit)
      case Some(raw) =>
        raw.toIntOption match
          case Some(v) if v >= 1 => Right(math.min(v, Constants.MaxPageLimit))
          case _ => Left(DomainError.ValidationError)

  /** Parses the offset query param, defaulting to 0. */
  private def parseOffset(s: Option[String]): Either[DomainError, Int] =
    s match
      case None => Right(0)
      case Some(raw) =>
        raw.toIntOption match
          case Some(v) if v >= 0 => Right(v)
          case _ => Left(DomainError.ValidationError)

/** Declarative tapir definitions of every API endpoint plus their server logic. */
object Api:
  import ApiCodecs.given

  private val errorOut: EndpointOutput[(StatusCode, ErrorResponse)] =
    statusCode.and(jsonBody[ErrorResponse])

  private val healthEndpoint: PublicEndpoint[Unit, Unit, Health, Any] =
    endpoint.get.in("api" / "health").out(jsonBody[Health])

  private val productListEndpoint: PublicEndpoint[
    (Option[String], Option[String], Option[String]),
    (StatusCode, ErrorResponse),
    ProductListResponse,
    Any
  ] =
    endpoint.get
      .in("api" / "products")
      .in(query[Option[String]]("query"))
      .in(query[Option[String]]("limit"))
      .in(query[Option[String]]("offset"))
      .out(jsonBody[ProductListResponse])
      .errorOut(errorOut)

  private val productCategoriesEndpoint: PublicEndpoint[Unit, (StatusCode, ErrorResponse), Vector[String], Any] =
    endpoint.get
      .in("api" / "products" / "categories")
      .out(jsonBody[Vector[String]])
      .errorOut(errorOut)

  private val productCreateEndpoint: PublicEndpoint[ProductInput, (StatusCode, ErrorResponse), ProductResponse, Any] =
    endpoint.post
      .in("api" / "products")
      .in(jsonBody[ProductInput])
      .out(jsonBody[ProductResponse])
      .errorOut(errorOut)

  private val productUpdateEndpoint
      : PublicEndpoint[(String, ProductInput), (StatusCode, ErrorResponse), ProductResponse, Any] =
    endpoint.put
      .in("api" / "products" / path[String]("id"))
      .in(jsonBody[ProductInput])
      .out(jsonBody[ProductResponse])
      .errorOut(errorOut)

  private val productDeleteEndpoint: PublicEndpoint[String, (StatusCode, ErrorResponse), Unit, Any] =
    endpoint.delete
      .in("api" / "products" / path[String]("id"))
      .out(statusCode(StatusCode.NoContent))
      .errorOut(errorOut)

  private val dishListEndpoint: PublicEndpoint[
    (Option[String], Option[String], Option[String]),
    (StatusCode, ErrorResponse),
    DishListResponse,
    Any
  ] =
    endpoint.get
      .in("api" / "dishes")
      .in(query[Option[String]]("query"))
      .in(query[Option[String]]("limit"))
      .in(query[Option[String]]("offset"))
      .out(jsonBody[DishListResponse])
      .errorOut(errorOut)

  private val dishGetEndpoint: PublicEndpoint[String, (StatusCode, ErrorResponse), DishDetailView, Any] =
    endpoint.get
      .in("api" / "dishes" / path[String]("id"))
      .out(jsonBody[DishDetailView])
      .errorOut(errorOut)

  private val dishCreateEndpoint: PublicEndpoint[DishInput, (StatusCode, ErrorResponse), DishDetailView, Any] =
    endpoint.post
      .in("api" / "dishes")
      .in(jsonBody[DishInput])
      .out(jsonBody[DishDetailView])
      .errorOut(errorOut)

  private val dishUpdateEndpoint
      : PublicEndpoint[(String, DishInput), (StatusCode, ErrorResponse), DishDetailView, Any] =
    endpoint.put
      .in("api" / "dishes" / path[String]("id"))
      .in(jsonBody[DishInput])
      .out(jsonBody[DishDetailView])
      .errorOut(errorOut)

  private val dishDeleteEndpoint: PublicEndpoint[String, (StatusCode, ErrorResponse), Unit, Any] =
    endpoint.delete
      .in("api" / "dishes" / path[String]("id"))
      .out(statusCode(StatusCode.NoContent))
      .errorOut(errorOut)

  /** Maps the typed effect channel to the HTTP error output: re-raises interruption, converts
    * [[pro.drsdgdbye.domain.DomainError]] via [[ApiErrors.toHttp]] and logs anything unexpected as an internal error.
    */
  private[api] def guarded[A](effect: IO[DomainError, A]): ZIO[Any, (StatusCode, ErrorResponse), A] =
    effect.catchAllCause { cause =>
      if cause.isInterrupted then ZIO.interrupt
      else
        cause.failureOption match
          case Some(e: DomainError) => ZIO.fail(ApiErrors.toHttp(e))
          case _ =>
            ZIO.logErrorCause("Unhandled error", cause) *> ZIO.fail(ApiErrors.toHttp(DomainError.InternalError))
    }

  private def parsePage(
      limit: Option[String],
      offset: Option[String]): ZIO[Any, (StatusCode, ErrorResponse), Pagination.Page] =
    ZIO.fromEither(Pagination.parse(limit, offset)).mapError(ApiErrors.toHttp)

  private def parseId(raw: String): ZIO[Any, (StatusCode, ErrorResponse), Long] =
    ZIO.fromEither(raw.toLongOption.toRight(DomainError.ValidationError)).mapError(ApiErrors.toHttp)

  private def productListLogic(svc: ProductService)(
      params: (Option[String], Option[String], Option[String])
  ): ZIO[Any, (StatusCode, ErrorResponse), ProductListResponse] =
    val (query, limit, offset) = params
    for
      page <- parsePage(limit, offset)
      items <- guarded(svc.list(UserId.default, query, page.limit, page.offset))
    yield ProductListResponse(items.map(ProductResponse.from))

  private def productCategoriesLogic(svc: ProductService): ZIO[Any, (StatusCode, ErrorResponse), Vector[String]] =
    guarded(svc.categories(UserId.default))

  private def productCreateLogic(svc: ProductService)(
      input: ProductInput
  ): ZIO[Any, (StatusCode, ErrorResponse), ProductResponse] =
    guarded(svc.create(UserId.default, input)).map(ProductResponse.from)

  private def productUpdateLogic(svc: ProductService)(
      params: (String, ProductInput)
  ): ZIO[Any, (StatusCode, ErrorResponse), ProductResponse] =
    val (id, input) = params
    for
      productId <- parseId(id)
      product <- guarded(svc.update(UserId.default, productId, input))
    yield ProductResponse.from(product)

  private def productDeleteLogic(svc: ProductService)(
      id: String
  ): ZIO[Any, (StatusCode, ErrorResponse), Unit] =
    for
      productId <- parseId(id)
      _ <- guarded(svc.archive(UserId.default, productId))
    yield ()

  private def dishListLogic(svc: DishService)(
      params: (Option[String], Option[String], Option[String])
  ): ZIO[Any, (StatusCode, ErrorResponse), DishListResponse] =
    val (query, limit, offset) = params
    for
      page <- parsePage(limit, offset)
      items <- guarded(svc.list(UserId.default, query, page.limit, page.offset))
    yield DishListResponse(items)

  private def dishGetLogic(svc: DishService)(
      id: String
  ): ZIO[Any, (StatusCode, ErrorResponse), DishDetailView] =
    for
      dishId <- parseId(id)
      detail <- guarded(svc.get(UserId.default, dishId))
    yield detail

  private def dishCreateLogic(svc: DishService)(
      input: DishInput
  ): ZIO[Any, (StatusCode, ErrorResponse), DishDetailView] =
    guarded(svc.create(UserId.default, input))

  private def dishUpdateLogic(svc: DishService)(
      params: (String, DishInput)
  ): ZIO[Any, (StatusCode, ErrorResponse), DishDetailView] =
    val (id, input) = params
    for
      dishId <- parseId(id)
      detail <- guarded(svc.update(UserId.default, dishId, input))
    yield detail

  private def dishDeleteLogic(svc: DishService)(
      id: String
  ): ZIO[Any, (StatusCode, ErrorResponse), Unit] =
    for
      dishId <- parseId(id)
      _ <- guarded(svc.delete(UserId.default, dishId))
    yield ()

  def publicEndpoints: List[AnyEndpoint] =
    List(
      healthEndpoint,
      productListEndpoint,
      productCategoriesEndpoint,
      productCreateEndpoint,
      productUpdateEndpoint,
      productDeleteEndpoint,
      dishListEndpoint,
      dishGetEndpoint,
      dishCreateEndpoint,
      dishUpdateEndpoint,
      dishDeleteEndpoint
    )

  def serverEndpoints(
      productService: ProductService,
      dishService: DishService
  ): List[ZServerEndpoint[Any, ZioStreams & WebSockets]] =
    List(
      healthEndpoint.zServerLogic[Any](_ => ZIO.succeed(Health("ok"))),
      productListEndpoint.zServerLogic(productListLogic(productService)),
      productCategoriesEndpoint.zServerLogic(_ => productCategoriesLogic(productService)),
      productCreateEndpoint.zServerLogic(productCreateLogic(productService)),
      productUpdateEndpoint.zServerLogic(productUpdateLogic(productService)),
      productDeleteEndpoint.zServerLogic(productDeleteLogic(productService)),
      dishListEndpoint.zServerLogic(dishListLogic(dishService)),
      dishGetEndpoint.zServerLogic(dishGetLogic(dishService)),
      dishCreateEndpoint.zServerLogic(dishCreateLogic(dishService)),
      dishUpdateEndpoint.zServerLogic(dishUpdateLogic(dishService)),
      dishDeleteEndpoint.zServerLogic(dishDeleteLogic(dishService))
    )
