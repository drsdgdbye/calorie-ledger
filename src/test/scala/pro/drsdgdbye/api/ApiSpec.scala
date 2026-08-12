package pro.drsdgdbye.api

import pro.drsdgdbye.api.ApiCodecs.given
import pro.drsdgdbye.domain.*
import pro.drsdgdbye.service.*
import pro.drsdgdbye.testkit.Samples
import sttp.model.StatusCode
import zio.*
import zio.json.*
import zio.test.*
import zio.test.Assertion.*

object ApiSpec extends ZIOSpecDefault:

  def spec: Spec[Any, Nothing] =
    suite("Api units")(
      suite("ApiErrors.toHttp")(
        test("maps every DomainError to a status and an English error body") {
          assertTrue(
            ApiErrors
              .toHttp(DomainError.ProductNotFound) == (StatusCode.NotFound -> ErrorResponse("Product not found")),
            ApiErrors.toHttp(DomainError.DishNotFound) == (StatusCode.NotFound -> ErrorResponse("Dish not found")),
            ApiErrors.toHttp(DomainError.ValidationError) == (StatusCode.BadRequest -> ErrorResponse("Invalid data")),
            ApiErrors.toHttp(DomainError.InternalError) ==
              (StatusCode.InternalServerError -> ErrorResponse("Internal server error"))
          )
        }
      ),
      suite("Pagination.parse")(
        test("applies defaults when the parameters are absent") {
          assertTrue(Pagination.parse(None, None) == Right(Pagination.Page(Constants.DefaultPageLimit, 0)))
        },
        test("parses explicit limit and offset") {
          assertTrue(Pagination.parse(Some("10"), Some("5")) == Right(Pagination.Page(10, 5)))
        },
        test("caps the limit at the configured maximum") {
          assertTrue(Pagination.parse(Some("1000"), None) == Right(Pagination.Page(Constants.MaxPageLimit, 0)))
        },
        test("keeps the limit at the boundary values") {
          assertTrue(Pagination.parse(Some("1"), None) == Right(Pagination.Page(1, 0)))
        },
        test("rejects zero, negative and non-numeric limits") {
          assertTrue(
            Pagination.parse(Some("0"), None).isLeft,
            Pagination.parse(Some("-1"), None).isLeft,
            Pagination.parse(Some("abc"), None).isLeft
          )
        },
        test("rejects negative and non-numeric offsets") {
          assertTrue(
            Pagination.parse(None, Some("-1")).isLeft,
            Pagination.parse(None, Some("xyz")).isLeft
          )
        }
      ),
      suite("ApiCodecs")(
        test("product units encode and decode, rejecting unknown values") {
          assertTrue(
            JsonEncoder[ProductUnit].encodeJson(ProductUnit.GRAM).toString == "\"GRAM\"",
            JsonDecoder[ProductUnit].decodeJson("\"ML\"") == Right(ProductUnit.ML),
            JsonDecoder[ProductUnit].decodeJson("\"BOGUS\"").isLeft
          )
        },
        test("import issue codes use the documented snake_case wire values") {
          assertTrue(
            JsonEncoder[ImportIssueCode].encodeJson(ImportIssueCode.InvalidRecord).toString == "\"invalid_record\"",
            JsonDecoder[ImportIssueCode].decodeJson("\"duplicate\"") == Right(ImportIssueCode.Duplicate),
            JsonDecoder[ImportIssueCode].decodeJson("\"invalid_unit\"") == Right(ImportIssueCode.InvalidUnit),
            JsonDecoder[ImportIssueCode].decodeJson("\"BOGUS\"").isLeft
          )
        },
        test("import result wire types round-trip through JSON") {
          val result =
            ProductImportResult(
              imported = 3,
              errors = Vector(
                ImportItemError(5, ImportIssueCode.InvalidCalories),
                ImportItemError(12, ImportIssueCode.Duplicate)
              )
            )
          assertTrue(
            JsonDecoder[ProductImportResult]
              .decodeJson(JsonEncoder[ProductImportResult].encodeJson(result).toString) == Right(result),
            JsonDecoder[ProductImportResult].decodeJson(
              """{"imported":1,"errors":[]}"""
            ) == Right(ProductImportResult(1, Vector.empty))
          )
        },
        test("simple wire types round-trip through JSON") {
          val product = ProductResponse(1L, "Рис", Some("Крупы"), ProductUnit.GRAM, 330, 7, 1, 74, isArchived = false)
          assertTrue(
            JsonDecoder[ProductResponse]
              .decodeJson(JsonEncoder[ProductResponse].encodeJson(product).toString) == Right(product),
            JsonDecoder[ErrorResponse].decodeJson("""{"error":"Invalid data"}""") == Right(
              ErrorResponse("Invalid data")
            ),
            JsonDecoder[Health].decodeJson("""{"status":"ok"}""") == Right(Health("ok"))
          )
        },
        test("complex wire types round-trip through JSON") {
          val productInput = ProductInput("Рис", Some("Крупы"), ProductUnit.GRAM, 330, 7, 1, 74)
          val dishInput = DishInput("Плов", 1200, Vector(DishIngredientInput(5L, 300)))
          val detail = DishDetailView(
            id = 1L,
            name = "Плов",
            cookedWeightGrams = 1200,
            ingredients = Vector(
              DishIngredientView(5L, "Рис", ProductUnit.GRAM, 300, 330, 7, 1, 74)
            ),
            totals = NutritionView(990, 21, 3, 222),
            per100 = NutritionView(83, 2, 0, 19)
          )
          assertTrue(
            JsonDecoder[ProductInput].decodeJson(JsonEncoder[ProductInput].encodeJson(productInput).toString) ==
              Right(productInput),
            JsonDecoder[DishInput].decodeJson(JsonEncoder[DishInput].encodeJson(dishInput).toString) == Right(
              dishInput
            ),
            JsonDecoder[DishDetailView].decodeJson(JsonEncoder[DishDetailView].encodeJson(detail).toString) == Right(
              detail
            ),
            JsonDecoder[DishIngredientView]
              .decodeJson(JsonEncoder[DishIngredientView].encodeJson(detail.ingredients.head).toString) ==
              Right(detail.ingredients.head),
            JsonDecoder[NutritionView].decodeJson("""{"calories":83,"protein":2,"fat":0,"carbs":19}""") ==
              Right(NutritionView(83, 2, 0, 19)),
            JsonDecoder[ProductListResponse].decodeJson("""{"items":[]}""") == Right(ProductListResponse(Vector.empty)),
            JsonDecoder[DishListResponse].decodeJson("""{"items":[]}""") == Right(DishListResponse(Vector.empty))
          )
        }
      ),
      suite("Api.guarded")(
        test("maps a DomainError to its HTTP pair") {
          assertZIO(Api.guarded(ZIO.fail(DomainError.ProductNotFound)).either)(
            isLeft(equalTo((StatusCode.NotFound, ErrorResponse("Product not found"))))
          )
        },
        test("converts an unexpected defect to an internal server error") {
          assertZIO(Api.guarded(ZIO.die(new RuntimeException("boom"))).either)(
            isLeft(equalTo((StatusCode.InternalServerError, ErrorResponse("Internal server error"))))
          )
        },
        test("re-raises interruption without mapping it") {
          assertZIO(Api.guarded(ZIO.interrupt).exit.map(_.isInterrupted))(isTrue)
        }
      ),
      suite("ProductResponse.from")(
        test("maps a domain product with a category") {
          val product = Samples.product(1L, "Рис", Some("Крупы"))
          assertTrue(
            ProductResponse.from(product) ==
              ProductResponse(1L, "Рис", Some("Крупы"), ProductUnit.GRAM, 330, 7, 1, 74, isArchived = false)
          )
        },
        test("maps a domain product without a category") {
          val product = Samples.product(1L, "Рис").copy(isArchived = true)
          assertTrue(
            ProductResponse.from(product) ==
              ProductResponse(1L, "Рис", None, ProductUnit.GRAM, 330, 7, 1, 74, isArchived = true)
          )
        }
      )
    )
