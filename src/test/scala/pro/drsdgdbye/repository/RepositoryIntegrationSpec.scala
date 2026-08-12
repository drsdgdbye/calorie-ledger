package pro.drsdgdbye.repository

import com.dimafeng.testcontainers.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import pro.drsdgdbye.config.{AdminDbConfig, DbConfig}
import pro.drsdgdbye.db.QuillContext
import pro.drsdgdbye.db.flyway.DbMigrator
import pro.drsdgdbye.domain.*
import zio.*
import zio.test.*

/** Integration tests for both repositories against a real PostgreSQL started via testcontainers. Flyway migrations from
  * the main classpath (including the default-user seed) are applied before the repositories are exercised.
  */
object RepositoryIntegrationSpec extends ZIOSpecDefault:

  private val userId: UserId = UserId.default

  private def decode[A](e: Either[DomainError, A]): A =
    e.fold(e => throw IllegalStateException(s"Test data violates domain invariants: $e"), identity)

  private def createProduct(
      repo: ProductRepository,
      name: String,
      category: Option[String] = None): ZIO[Any, Throwable, Product] =
    repo.create(
      userId,
      decode(ProductName.from(name)),
      category.map(c => decode(CategoryName.from(c))),
      ProductUnit.GRAM,
      decode(Calories.from(330)),
      decode(Protein.from(7)),
      decode(Fat.from(1)),
      decode(Carbs.from(74))
    )

  private def ingredient(productId: ProductId, quantity: Int, position: Int): NewDishIngredient =
    NewDishIngredient(
      decode(ProductId.from(productId.value)),
      decode(Quantity.from(quantity)),
      decode(Position.from(position))
    )

  private def createDish(
      repo: DishRepository,
      name: String,
      weight: Int,
      ingredients: Vector[NewDishIngredient]): ZIO[Any, Throwable, DishWithIngredients] =
    repo.create(userId, decode(DishName.from(name)), decode(Weight.from(weight)), ingredients)

  /** Starts a shared Postgres container, released when the layer's scope closes. */
  private val containerLayer: ZLayer[Any, Throwable, PostgreSQLContainer] =
    ZLayer.scoped {
      ZIO.acquireRelease(
        ZIO.attemptBlocking {
          val container = PostgreSQLContainer(DockerImageName.parse("postgres:16"))
          container.start()
          container
        }
      )(container => ZIO.attemptBlocking(container.stop()).orDie)
    }

  /** Points [[DbConfig]] at the container instead of the configured application database. */
  private val dbConfigLayer: ZLayer[PostgreSQLContainer, Nothing, DbConfig] =
    ZLayer.fromFunction { (container: PostgreSQLContainer) =>
      DbConfig(
        url = container.jdbcUrl,
        user = container.username,
        password = container.password,
        poolSize = 2,
        admin = AdminDbConfig(container.jdbcUrl, container.username, container.password)
      )
    }

  /** Applies the Flyway migrations, then exposes the same config unchanged so the data source is created afterwards. */
  private val migratedConfigLayer: ZLayer[DbConfig, Throwable, DbConfig] =
    ZLayer.fromZIO {
      for
        cfg <- ZIO.service[DbConfig]
        _ <- DbMigrator.migrate(cfg)
      yield cfg
    }

  /** Test stack: container -> config -> migrations -> Hikari data source -> Quill context -> repositories. */
  private val testLayer: ZLayer[Any, Throwable, ProductRepository & DishRepository] =
    (containerLayer >>> dbConfigLayer >>> migratedConfigLayer)
      >>> QuillContext.dataSourceLayer
      >>> QuillContext.ctxLayer
      >>> (ProductRepositoryLive.layer ++ DishRepositoryLive.layer)

  private val productSuite: Spec[ProductRepository, Throwable] =
    suite("ProductRepository")(
      test("create inserts a product and returns it with a generated id") {
        for
          repo <- ZIO.service[ProductRepository]
          product <- createProduct(repo, "Овсянка", Some("Каши"))
        yield assertTrue(
          product.id.value > 0L,
          product.userId == userId,
          product.name.value == "Овсянка",
          product.category.map(_.value) == Some("Каши"),
          product.caloriesPer100.value == 330,
          !product.isArchived
        )
      },
      test("create persists the product so findActive returns it") {
        for
          repo <- ZIO.service[ProductRepository]
          product <- createProduct(repo, "Перловка")
          found <- repo.findActive(userId, product.id)
        yield assertTrue(found.map(_.id) == Some(product.id))
      },
      test("findActive returns None for a missing product") {
        for
          repo <- ZIO.service[ProductRepository]
          found <- repo.findActive(userId, decode(ProductId.from(999999L)))
        yield assertTrue(found.isEmpty)
      },
      test("findActive hides archived products") {
        for
          repo <- ZIO.service[ProductRepository]
          product <- createProduct(repo, "Архивный")
          _ <- repo.archive(userId, product.id)
          found <- repo.findActive(userId, product.id)
        yield assertTrue(found.isEmpty)
      },
      test("list filters by case-insensitive prefix, excludes archived and sorts by name") {
        for
          repo <- ZIO.service[ProductRepository]
          _ <- createProduct(repo, "Рис", Some("Крупы"))
          _ <- createProduct(repo, "Рисотто", Some("Крупы"))
          _ <- createProduct(repo, "Гречка", Some("Крупы"))
          archived <- createProduct(repo, "Рисовая каша", Some("Крупы"))
          _ <- repo.archive(userId, archived.id)
          byPrefix <- repo.list(userId, Some("рис"), 100, 0)
          all <- repo.list(userId, None, 100, 0)
        yield assertTrue(
          byPrefix.map(_.name.value) == Vector("Рис", "Рисотто"),
          all.map(_.name.value).contains("Гречка"),
          !all.map(_.name.value).contains("Рисовая каша")
        )
      },
      test("list supports limit and offset pagination") {
        for
          repo <- ZIO.service[ProductRepository]
          _ <- createProduct(repo, "Паг1")
          _ <- createProduct(repo, "Паг2")
          _ <- createProduct(repo, "Паг3")
          page1 <- repo.list(userId, Some("паг"), 2, 0)
          page2 <- repo.list(userId, Some("паг"), 2, 2)
        yield assertTrue(
          page1.map(_.name.value) == Vector("Паг1", "Паг2"),
          page2.map(_.name.value) == Vector("Паг3")
        )
      },
      test("categories returns distinct non-null categories, excluding archived") {
        for
          repo <- ZIO.service[ProductRepository]
          _ <- createProduct(repo, "Творог", Some("Молочка"))
          _ <- createProduct(repo, "Сыр", Some("Молочка"))
          _ <- createProduct(repo, "Курица", Some("Мясо"))
          _ <- createProduct(repo, "Соль", None)
          doomed <- createProduct(repo, "Просрочка", Some("УникальнаяКатегория"))
          _ <- repo.archive(userId, doomed.id)
          categories <- repo.categories(userId)
        yield assertTrue(
          categories == categories.distinct,
          categories == categories.sorted,
          categories.contains("Молочка"),
          categories.contains("Мясо"),
          !categories.contains("УникальнаяКатегория")
        )
      },
      test("update changes the fields and returns the updated product") {
        for
          repo <- ZIO.service[ProductRepository]
          product <- createProduct(repo, "Сметана", Some("Молочка"))
          updated <- repo.update(
            userId,
            product.id,
            decode(ProductName.from("Сметана 20%")),
            None,
            ProductUnit.ML,
            decode(Calories.from(200)),
            decode(Protein.from(3)),
            decode(Fat.from(20)),
            decode(Carbs.from(3))
          )
          fetched <- ZIO.fromOption(updated).orElseFail(IllegalStateException("update did not return the product"))
        yield assertTrue(
          fetched.name.value == "Сметана 20%",
          fetched.unit == ProductUnit.ML,
          fetched.caloriesPer100.value == 200,
          fetched.fatPer100.value == 20
        )
      },
      test("update returns None for a missing or archived product") {
        for
          repo <- ZIO.service[ProductRepository]
          product <- createProduct(repo, "Сгущёнка", Some("Молочка"))
          _ <- repo.archive(userId, product.id)
          missing <- repo.update(
            userId,
            decode(ProductId.from(999999L)),
            decode(ProductName.from("Нет")),
            None,
            ProductUnit.GRAM,
            decode(Calories.from(1)),
            decode(Protein.from(1)),
            decode(Fat.from(1)),
            decode(Carbs.from(1))
          )
          archived <- repo.update(
            userId,
            product.id,
            decode(ProductName.from("Сгущёнка")),
            None,
            ProductUnit.GRAM,
            decode(Calories.from(1)),
            decode(Protein.from(1)),
            decode(Fat.from(1)),
            decode(Carbs.from(1))
          )
        yield assertTrue(missing.isEmpty, archived.isEmpty)
      },
      test("archive flags the product and is idempotent") {
        for
          repo <- ZIO.service[ProductRepository]
          product <- createProduct(repo, "Кефир", Some("Молочка"))
          first <- repo.archive(userId, product.id)
          second <- repo.archive(userId, product.id)
          found <- repo.findActive(userId, product.id)
        yield assertTrue(first, !second, found.isEmpty)
      },
      test("findExistingIds returns only the ids that exist for the user") {
        for
          repo <- ZIO.service[ProductRepository]
          yogurt <- createProduct(repo, "Йогурт")
          cream <- createProduct(repo, "Сливки")
          found <- repo.findExistingIds(userId, Vector(yogurt.id, cream.id, decode(ProductId.from(999999L))))
        yield assertTrue(found.toSet == Set(yogurt.id, cream.id))
      }
    )

  private val dishSuite: Spec[ProductRepository & DishRepository, Throwable] =
    suite("DishRepository")(
      test("create persists a dish with ingredients ordered by position") {
        for
          products <- ZIO.service[ProductRepository]
          dishes <- ZIO.service[DishRepository]
          rice <- createProduct(products, "Пловрис", Some("Крупы"))
          carrot <- createProduct(products, "Пловморковь", Some("Овощи"))
          dish <- createDish(
            dishes,
            "Плов",
            1200,
            Vector(ingredient(rice.id, 300, 0), ingredient(carrot.id, 100, 1))
          )
          fetched <- dishes.getWithIngredients(userId, dish.dish.id)
        yield assertTrue(
          dish.dish.name.value == "Плов",
          dish.dish.cookedWeightGrams.value == 1200,
          dish.ingredients.map(_.position.value) == Vector(0, 1),
          dish.ingredients.map(_.productId) == Vector(rice.id, carrot.id),
          dish.ingredients.map(_.productName.value) == Vector("Пловрис", "Пловморковь"),
          fetched.map(_.ingredients.size) == Some(2)
        )
      },
      test("getWithIngredients returns None for a missing dish") {
        for
          dishes <- ZIO.service[DishRepository]
          none <- dishes.getWithIngredients(userId, decode(DishId.from(999999L)))
        yield assertTrue(none.isEmpty)
      },
      test("list returns dishes together with their ingredients") {
        for
          products <- ZIO.service[ProductRepository]
          dishes <- ZIO.service[DishRepository]
          potato <- createProduct(products, "Спислокартошка")
          cabbage <- createProduct(products, "Спислокочан")
          _ <- createDish(
            dishes,
            "Списокрагу",
            800,
            Vector(ingredient(potato.id, 500, 0), ingredient(cabbage.id, 300, 1))
          )
          _ <- createDish(dishes, "Списоксуп", 600, Vector(ingredient(potato.id, 400, 0)))
          byPrefix <- dishes.listWithIngredients(userId, Some("список"), 100, 0)
        yield assertTrue(
          byPrefix.map(_.dish.name.value) == Vector("Списокрагу", "Списоксуп"),
          byPrefix.forall(_.ingredients.nonEmpty)
        )
      },
      test("update replaces the dish fields and the full ingredient set") {
        for
          products <- ZIO.service[ProductRepository]
          dishes <- ZIO.service[DishRepository]
          rice <- createProduct(products, "Обноврис", Some("Крупы"))
          carrot <- createProduct(products, "Обновморковь", Some("Овощи"))
          onion <- createProduct(products, "Обновлук", Some("Овощи"))
          dish <- createDish(
            dishes,
            "Обнов",
            1000,
            Vector(ingredient(rice.id, 200, 0), ingredient(carrot.id, 100, 1))
          )
          updated <- dishes.update(
            userId,
            dish.dish.id,
            decode(DishName.from("Обнов2")),
            decode(Weight.from(900)),
            Vector(ingredient(onion.id, 50, 0))
          )
          fetched <- ZIO.fromOption(updated).orElseFail(IllegalStateException("update did not return the dish"))
        yield assertTrue(
          fetched.dish.name.value == "Обнов2",
          fetched.dish.cookedWeightGrams.value == 900,
          fetched.ingredients.map(_.productId) == Vector(onion.id),
          fetched.ingredients.map(_.position.value) == Vector(0)
        )
      },
      test("update returns None for a missing dish") {
        for
          dishes <- ZIO.service[DishRepository]
          none <- dishes.update(
            userId,
            decode(DishId.from(999999L)),
            decode(DishName.from("Нет")),
            decode(Weight.from(100)),
            Vector.empty
          )
        yield assertTrue(none.isEmpty)
      },
      test("delete removes the dish and reports whether it existed") {
        for
          products <- ZIO.service[ProductRepository]
          dishes <- ZIO.service[DishRepository]
          onion <- createProduct(products, "Удаллук", Some("Овощи"))
          dish <- createDish(dishes, "Удалить", 500, Vector(ingredient(onion.id, 100, 0)))
          first <- dishes.delete(userId, dish.dish.id)
          second <- dishes.delete(userId, dish.dish.id)
          fetched <- dishes.getWithIngredients(userId, dish.dish.id)
        yield assertTrue(first, !second, fetched.isEmpty)
      }
    )

  def spec: Spec[TestEnvironment, Throwable] =
    suite("Repository integration")(productSuite, dishSuite).provideLayer(testLayer)
