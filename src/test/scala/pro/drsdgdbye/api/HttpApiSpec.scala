package pro.drsdgdbye.api

import pro.drsdgdbye.api.ApiCodecs.given
import pro.drsdgdbye.domain.*
import pro.drsdgdbye.service.*
import pro.drsdgdbye.testkit.{Samples, StubDishService, StubProductService}
import pro.drsdgdbye.App
import zio.*
import zio.http.*
import zio.json.*
import zio.test.*
import zio.test.TestAspect.*

object HttpApiSpec extends ZIOSpecDefault:

  import Samples.*

  private val productJson: String =
    """{"name":"Рис","category":"Крупы","unit":"GRAM","caloriesPer100":330,"proteinPer100":7,"fatPer100":1,"carbsPer100":74}"""

  private val dishJson: String =
    """{"name":"Плов","cookedWeightGrams":1200,"ingredients":[{"productId":5,"quantity":300},{"productId":6,"quantity":100}]}"""

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

  private val plovWire: ProductResponse =
    ProductResponse(1L, "Рис", Some("Крупы"), ProductUnit.GRAM, 330, 7, 1, 74, isArchived = false)

  private def send(method: Method, url: String, json: String): Request =
    val base = method match
      case Method.POST => Request.post(url, Body.fromString(json))
      case Method.PUT => Request.put(url, Body.fromString(json))
      case other => throw new IllegalArgumentException(s"Unsupported method $other")
    base.updateHeaders(_ ++ Headers(Header.ContentType(MediaType.application.json)))

  private def withServer[A](
      products: ProductService = StubProductService(),
      dishes: DishService = StubDishService()
  )(op: Int => ZIO[Client, Throwable, A]): ZIO[Any, Throwable, A] =
    (for
      port <- Server.install(App.routes(products, dishes))
      result <- op(port)
    yield result).provide(Server.defaultWithPort(0), Client.default)

  private def statusAndBody(response: Response): ZIO[Any, Throwable, (Status, String)] =
    response.body.asString.map(body => (response.status, body))

  def spec: Spec[Any, Throwable] =
    suite("HttpApi")(
      suite("health")(
        test("GET /api/health returns ok") {
          for
            response <- withServer() { port => Client.batched(Request.get(s"http://localhost:$port/api/health")) }
            result <- statusAndBody(response)
          yield assertTrue(result == ((Status.Ok, """{"status":"ok"}""")))
        }
      ),
      suite("products list")(
        test("GET /api/products lists products") {
          val stub = StubProductService(
            listF = (_, _, _, _) => ZIO.succeed(Vector(product(1L, "Рис", Some("Крупы"))))
          )
          for
            response <- withServer(products = stub) { port =>
              Client.batched(Request.get(s"http://localhost:$port/api/products"))
            }
            body <- response.body.asString
          yield assertTrue(
            response.status == Status.Ok,
            JsonDecoder[ProductListResponse].decodeJson(body) ==
              Right(ProductListResponse(Vector(plovWire)))
          )
        },
        test("GET /api/products forwards the parsed pagination") {
          for
            captured <- Ref.make(Option.empty[(Option[String], Int, Int)])
            stub = StubProductService(
              listF = (_, q, l, o) => captured.set(Some((q, l, o))) *> ZIO.succeed(Vector.empty)
            )
            response <- withServer(products = stub) { port =>
              Client.batched(Request.get(s"http://localhost:$port/api/products?query=рис&limit=2&offset=1"))
            }
            args <- captured.get
          yield assertTrue(response.status == Status.Ok, args.contains((Some("рис"), 2, 1)))
        },
        test("GET /api/products caps an oversized limit") {
          for
            captured <- Ref.make(Option.empty[Int])
            stub = StubProductService(
              listF = (_, _, l, _) => captured.set(Some(l)) *> ZIO.succeed(Vector.empty)
            )
            response <- withServer(products = stub) { port =>
              Client.batched(Request.get(s"http://localhost:$port/api/products?limit=1000"))
            }
            limit <- captured.get
          yield assertTrue(response.status == Status.Ok, limit.contains(Constants.MaxPageLimit))
        },
        test("GET /api/products rejects an invalid limit") {
          for
            response <- withServer() { port =>
              Client.batched(Request.get(s"http://localhost:$port/api/products?limit=abc"))
            }
            result <- statusAndBody(response)
          yield assertTrue(
            result == ((Status.BadRequest, """{"error":"Invalid data"}"""))
          )
        },
        test("GET /api/products rejects a negative offset") {
          for
            response <- withServer() { port =>
              Client.batched(Request.get(s"http://localhost:$port/api/products?offset=-1"))
            }
            result <- statusAndBody(response)
          yield assertTrue(result._1 == Status.BadRequest)
        }
      ),
      suite("products categories")(
        test("GET /api/products/categories returns categories") {
          val stub = StubProductService(categoriesF = _ => ZIO.succeed(Vector("Мясо", "Крупы")))
          for
            response <- withServer(products = stub) { port =>
              Client.batched(Request.get(s"http://localhost:$port/api/products/categories"))
            }
            body <- response.body.asString
          yield assertTrue(
            response.status == Status.Ok,
            JsonDecoder[Vector[String]].decodeJson(body) == Right(Vector("Мясо", "Крупы"))
          )
        },
        test("GET /api/products/categories maps a service defect to 500") {
          val stub = StubProductService(categoriesF = _ => ZIO.die(new RuntimeException("boom")))
          for
            response <- withServer(products = stub) { port =>
              Client.batched(Request.get(s"http://localhost:$port/api/products/categories"))
            }
            result <- statusAndBody(response)
          yield assertTrue(
            result == ((Status.InternalServerError, """{"error":"Internal server error"}"""))
          )
        }
      ),
      suite("products create")(
        test("POST /api/products creates a product") {
          val stub = StubProductService(
            createF = (_, input) => ZIO.succeed(product(5L, input.name, input.category))
          )
          for
            response <- withServer(products = stub) { port =>
              Client.batched(send(Method.POST, s"http://localhost:$port/api/products", productJson))
            }
            body <- response.body.asString
          yield assertTrue(
            response.status == Status.Ok,
            JsonDecoder[ProductResponse].decodeJson(body) ==
              Right(ProductResponse(5L, "Рис", Some("Крупы"), ProductUnit.GRAM, 330, 7, 1, 74, isArchived = false))
          )
        },
        test("POST /api/products maps a ValidationError to 400") {
          val stub = StubProductService(createF = (_, _) => ZIO.fail(DomainError.ValidationError))
          for
            response <- withServer(products = stub) { port =>
              Client.batched(send(Method.POST, s"http://localhost:$port/api/products", productJson))
            }
            result <- statusAndBody(response)
          yield assertTrue(result == ((Status.BadRequest, """{"error":"Invalid data"}""")))
        },
        test("POST /api/products maps an InternalError to 500") {
          for
            response <- withServer() { port =>
              Client.batched(send(Method.POST, s"http://localhost:$port/api/products", productJson))
            }
            result <- statusAndBody(response)
          yield assertTrue(result == ((Status.InternalServerError, """{"error":"Internal server error"}""")))
        }
      ),
      suite("products update")(
        test("PUT /api/products/{id} updates a product") {
          val stub = StubProductService(
            updateF = (_, _, input) => ZIO.succeed(product(9L, input.name, input.category))
          )
          for
            response <- withServer(products = stub) { port =>
              Client.batched(send(Method.PUT, s"http://localhost:$port/api/products/9", productJson))
            }
            result <- statusAndBody(response)
          yield assertTrue(
            result._1 == Status.Ok,
            result._2.contains("Рис")
          )
        },
        test("PUT /api/products/{id} rejects a non-numeric id") {
          for
            response <- withServer() { port =>
              Client.batched(send(Method.PUT, s"http://localhost:$port/api/products/abc", productJson))
            }
            result <- statusAndBody(response)
          yield assertTrue(result == ((Status.BadRequest, """{"error":"Invalid data"}""")))
        },
        test("PUT /api/products/{id} maps ProductNotFound to 404") {
          val stub = StubProductService(updateF = (_, _, _) => ZIO.fail(DomainError.ProductNotFound))
          for
            response <- withServer(products = stub) { port =>
              Client.batched(send(Method.PUT, s"http://localhost:$port/api/products/9", productJson))
            }
            result <- statusAndBody(response)
          yield assertTrue(result == ((Status.NotFound, """{"error":"Product not found"}""")))
        }
      ),
      suite("products delete")(
        test("DELETE /api/products/{id} archives and returns 204") {
          for
            response <- withServer() { port =>
              Client.batched(Request.delete(s"http://localhost:$port/api/products/9"))
            }
            body <- response.body.asString
          yield assertTrue(response.status == Status.NoContent, body.isEmpty)
        },
        test("DELETE /api/products/{id} rejects a non-numeric id") {
          for
            response <- withServer() { port =>
              Client.batched(Request.delete(s"http://localhost:$port/api/products/abc"))
            }
            result <- statusAndBody(response)
          yield assertTrue(result == ((Status.BadRequest, """{"error":"Invalid data"}""")))
        },
        test("DELETE /api/products/{id} maps ProductNotFound to 404") {
          val stub = StubProductService(archiveF = (_, _) => ZIO.fail(DomainError.ProductNotFound))
          for
            response <- withServer(products = stub) { port =>
              Client.batched(Request.delete(s"http://localhost:$port/api/products/9"))
            }
            result <- statusAndBody(response)
          yield assertTrue(result == ((Status.NotFound, """{"error":"Product not found"}""")))
        }
      ),
      suite("dishes list")(
        test("GET /api/dishes lists dishes") {
          val stub = StubDishService(
            listF = (_, _, _, _) => ZIO.succeed(Vector(DishListItemView(1L, "Плов", 1200, 83)))
          )
          for
            response <- withServer(dishes = stub) { port =>
              Client.batched(Request.get(s"http://localhost:$port/api/dishes"))
            }
            body <- response.body.asString
          yield assertTrue(
            response.status == Status.Ok,
            JsonDecoder[DishListResponse].decodeJson(body) ==
              Right(DishListResponse(Vector(DishListItemView(1L, "Плов", 1200, 83))))
          )
        },
        test("GET /api/dishes rejects an invalid limit") {
          for
            response <- withServer() { port =>
              Client.batched(Request.get(s"http://localhost:$port/api/dishes?limit=xyz"))
            }
            result <- statusAndBody(response)
          yield assertTrue(result == ((Status.BadRequest, """{"error":"Invalid data"}""")))
        }
      ),
      suite("dishes get")(
        test("GET /api/dishes/{id} returns the detail") {
          val stub = StubDishService(getF = (_, _) => ZIO.succeed(plovDetail))
          for
            response <- withServer(dishes = stub) { port =>
              Client.batched(Request.get(s"http://localhost:$port/api/dishes/1"))
            }
            body <- response.body.asString
          yield assertTrue(
            response.status == Status.Ok,
            JsonDecoder[DishDetailView].decodeJson(body) == Right(plovDetail)
          )
        },
        test("GET /api/dishes/{id} rejects a non-numeric id") {
          for
            response <- withServer() { port =>
              Client.batched(Request.get(s"http://localhost:$port/api/dishes/abc"))
            }
            result <- statusAndBody(response)
          yield assertTrue(result == ((Status.BadRequest, """{"error":"Invalid data"}""")))
        },
        test("GET /api/dishes/{id} maps DishNotFound to 404") {
          for
            response <- withServer() { port =>
              Client.batched(Request.get(s"http://localhost:$port/api/dishes/42"))
            }
            result <- statusAndBody(response)
          yield assertTrue(result == ((Status.NotFound, """{"error":"Dish not found"}""")))
        }
      ),
      suite("dishes create")(
        test("POST /api/dishes creates a dish") {
          val stub = StubDishService(createF = (_, _) => ZIO.succeed(plovDetail))
          for
            response <- withServer(dishes = stub) { port =>
              Client.batched(send(Method.POST, s"http://localhost:$port/api/dishes", dishJson))
            }
            body <- response.body.asString
          yield assertTrue(
            response.status == Status.Ok,
            JsonDecoder[DishDetailView].decodeJson(body) == Right(plovDetail)
          )
        },
        test("POST /api/dishes maps a ValidationError to 400") {
          val stub = StubDishService(createF = (_, _) => ZIO.fail(DomainError.ValidationError))
          for
            response <- withServer(dishes = stub) { port =>
              Client.batched(send(Method.POST, s"http://localhost:$port/api/dishes", dishJson))
            }
            result <- statusAndBody(response)
          yield assertTrue(result == ((Status.BadRequest, """{"error":"Invalid data"}""")))
        }
      ),
      suite("dishes update")(
        test("PUT /api/dishes/{id} updates a dish") {
          val stub = StubDishService(updateF = (_, _, _) => ZIO.succeed(plovDetail))
          for
            response <- withServer(dishes = stub) { port =>
              Client.batched(send(Method.PUT, s"http://localhost:$port/api/dishes/1", dishJson))
            }
            body <- response.body.asString
          yield assertTrue(
            response.status == Status.Ok,
            JsonDecoder[DishDetailView].decodeJson(body) == Right(plovDetail)
          )
        },
        test("PUT /api/dishes/{id} rejects a non-numeric id") {
          for
            response <- withServer() { port =>
              Client.batched(send(Method.PUT, s"http://localhost:$port/api/dishes/abc", dishJson))
            }
            result <- statusAndBody(response)
          yield assertTrue(result == ((Status.BadRequest, """{"error":"Invalid data"}""")))
        },
        test("PUT /api/dishes/{id} maps DishNotFound to 404") {
          val stub = StubDishService(updateF = (_, _, _) => ZIO.fail(DomainError.DishNotFound))
          for
            response <- withServer(dishes = stub) { port =>
              Client.batched(send(Method.PUT, s"http://localhost:$port/api/dishes/1", dishJson))
            }
            result <- statusAndBody(response)
          yield assertTrue(result == ((Status.NotFound, """{"error":"Dish not found"}""")))
        }
      ),
      suite("dishes delete")(
        test("DELETE /api/dishes/{id} returns 204") {
          for
            response <- withServer() { port =>
              Client.batched(Request.delete(s"http://localhost:$port/api/dishes/1"))
            }
            body <- response.body.asString
          yield assertTrue(response.status == Status.NoContent, body.isEmpty)
        },
        test("DELETE /api/dishes/{id} rejects a non-numeric id") {
          for
            response <- withServer() { port =>
              Client.batched(Request.delete(s"http://localhost:$port/api/dishes/abc"))
            }
            result <- statusAndBody(response)
          yield assertTrue(result == ((Status.BadRequest, """{"error":"Invalid data"}""")))
        },
        test("DELETE /api/dishes/{id} maps DishNotFound to 404") {
          val stub = StubDishService(deleteF = (_, _) => ZIO.fail(DomainError.DishNotFound))
          for
            response <- withServer(dishes = stub) { port =>
              Client.batched(Request.delete(s"http://localhost:$port/api/dishes/1"))
            }
            result <- statusAndBody(response)
          yield assertTrue(result == ((Status.NotFound, """{"error":"Dish not found"}""")))
        }
      ),
      suite("static routes")(
        test("GET / serves the frontend index") {
          for
            response <- withServer() { port => Client.batched(Request.get(s"http://localhost:$port/")) }
            body <- response.body.asString
          yield assertTrue(response.status == Status.Ok, body.toLowerCase.contains("<!doctype html>"))
        },
        test("GET /missing.js returns 404") {
          for
            response <- withServer() { port =>
              Client.batched(Request.get(s"http://localhost:$port/missing.js"))
            }
            result <- statusAndBody(response)
          yield assertTrue(result._1 == Status.NotFound)
        }
      )
    ) @@ withLiveClock
