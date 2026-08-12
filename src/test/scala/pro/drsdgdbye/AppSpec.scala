package pro.drsdgdbye

import pro.drsdgdbye.domain.*
import pro.drsdgdbye.service.*
import zio.*
import zio.test.*

object AppSpec extends ZIOSpecDefault:

  private val stubProductService: ProductService = new ProductService:
    override def list(_userId: UserId, _query: Option[String], _limit: Int, _offset: Int) =
      ZIO.succeed(Vector.empty[Product])

    override def categories(_userId: UserId) = ZIO.succeed(Vector.empty[String])

    override def create(_userId: UserId, _input: ProductInput) = ZIO.fail(DomainError.InternalError)

    override def update(_userId: UserId, _productId: Long, _input: ProductInput) = ZIO.fail(DomainError.InternalError)

    override def archive(_userId: UserId, _productId: Long) = ZIO.unit

  private val stubDishService: DishService = new DishService:
    override def list(_userId: UserId, _query: Option[String], _limit: Int, _offset: Int) =
      ZIO.succeed(Vector.empty[DishListItemView])

    override def get(_userId: UserId, _dishId: Long) = ZIO.fail(DomainError.DishNotFound)

    override def create(_userId: UserId, _input: DishInput) = ZIO.fail(DomainError.InternalError)

    override def update(_userId: UserId, _dishId: Long, _input: DishInput) = ZIO.fail(DomainError.InternalError)

    override def delete(_userId: UserId, _dishId: Long) = ZIO.unit

  def spec: Spec[Any, Nothing] =
    suite("App")(
      test("assembles Swagger routes from the public endpoint descriptions") {
        assertTrue(App.swaggerRoutes.routes.nonEmpty)
      },
      test("assembles API, Swagger and static routes without a database") {
        assertTrue(App.routes(stubProductService, stubDishService).routes.nonEmpty)
      }
    )
