package pro.drsdgdbye.testkit

import pro.drsdgdbye.domain.*
import pro.drsdgdbye.service.*
import zio.*

/** In-memory [[ProductService]] with per-test behavior overridable through function fields. */
final case class StubProductService(
    listF: (UserId, Option[String], Int, Int) => IO[DomainError, Vector[Product]] = (_, _, _, _) =>
      ZIO.succeed(Vector.empty),
    categoriesF: UserId => IO[DomainError, Vector[String]] = _ => ZIO.succeed(Vector.empty),
    createF: (UserId, ProductInput) => IO[DomainError, Product] = (_, _) => ZIO.fail(DomainError.InternalError),
    updateF: (UserId, Long, ProductInput) => IO[DomainError, Product] = (_, _, _) =>
      ZIO.fail(DomainError.InternalError),
    importF: (UserId, Vector[ImportItem]) => IO[DomainError, ProductImportResult] = (_, _) =>
      ZIO.fail(DomainError.InternalError),
    archiveF: (UserId, Long) => IO[DomainError, Unit] = (_, _) => ZIO.unit
) extends ProductService:

  override def list(userId: UserId, query: Option[String], limit: Int, offset: Int): IO[DomainError, Vector[Product]] =
    listF(userId, query, limit, offset)

  override def categories(userId: UserId): IO[DomainError, Vector[String]] = categoriesF(userId)

  override def create(userId: UserId, input: ProductInput): IO[DomainError, Product] = createF(userId, input)

  override def update(userId: UserId, productId: Long, input: ProductInput): IO[DomainError, Product] =
    updateF(userId, productId, input)

  override def importProducts(userId: UserId, items: Vector[ImportItem]): IO[DomainError, ProductImportResult] =
    importF(userId, items)

  override def archive(userId: UserId, productId: Long): IO[DomainError, Unit] = archiveF(userId, productId)

/** In-memory [[DishService]] with per-test behavior overridable through function fields. */
final case class StubDishService(
    listF: (UserId, Option[String], Int, Int) => IO[DomainError, Vector[DishListItemView]] = (_, _, _, _) =>
      ZIO.succeed(Vector.empty),
    getF: (UserId, Long) => IO[DomainError, DishDetailView] = (_, _) => ZIO.fail(DomainError.DishNotFound),
    createF: (UserId, DishInput) => IO[DomainError, DishDetailView] = (_, _) => ZIO.fail(DomainError.InternalError),
    updateF: (UserId, Long, DishInput) => IO[DomainError, DishDetailView] = (_, _, _) =>
      ZIO.fail(DomainError.InternalError),
    deleteF: (UserId, Long) => IO[DomainError, Unit] = (_, _) => ZIO.unit
) extends DishService:

  override def list(userId: UserId, query: Option[String], limit: Int, offset: Int): IO[DomainError, Vector[
    DishListItemView
  ]] =
    listF(userId, query, limit, offset)

  override def get(userId: UserId, dishId: Long): IO[DomainError, DishDetailView] = getF(userId, dishId)

  override def create(userId: UserId, input: DishInput): IO[DomainError, DishDetailView] = createF(userId, input)

  override def update(userId: UserId, dishId: Long, input: DishInput): IO[DomainError, DishDetailView] =
    updateF(userId, dishId, input)

  override def delete(userId: UserId, dishId: Long): IO[DomainError, Unit] = deleteF(userId, dishId)
