package pro.drsdgdbye.domain

import java.time.Instant

/** Identifier of a product. */
opaque type ProductId = Long

object ProductId:
  def from(id: Long): Either[DomainError, ProductId] = Validation.positiveId(id)
  extension (id: ProductId) def value: Long = id

/** Non-empty trimmed product name, max [[Constants.MaxNameLength]] characters. */
opaque type ProductName = String

object ProductName:
  def from(s: String): Either[DomainError, ProductName] = Validation.trimmed(s, Constants.MaxNameLength)
  extension (n: ProductName) def value: String = n

/** Non-empty trimmed product category, max [[Constants.MaxCategoryLength]] characters. */
opaque type CategoryName = String

object CategoryName:
  def from(s: String): Either[DomainError, CategoryName] = Validation.trimmed(s, Constants.MaxCategoryLength)
  extension (c: CategoryName) def value: String = c

/** Calories per 100 units of product, bounded by [[Constants.MaxNutrientPer100]]. */
opaque type Calories = Int

object Calories:
  def from(v: Int): Either[DomainError, Calories] = Validation.nonNegative(v, Constants.MaxNutrientPer100)
  extension (c: Calories) def value: Int = c

/** Carbs in grams per 100 units of product, bounded by [[Constants.MaxNutrientPer100]]. */
opaque type Carbs = Int

object Carbs:
  def from(v: Int): Either[DomainError, Carbs] = Validation.nonNegative(v, Constants.MaxNutrientPer100)
  extension (c: Carbs) def value: Int = c

/** Fat in grams per 100 units of product, bounded by [[Constants.MaxNutrientPer100]]. */
opaque type Fat = Int

object Fat:
  def from(v: Int): Either[DomainError, Fat] = Validation.nonNegative(v, Constants.MaxNutrientPer100)
  extension (f: Fat) def value: Int = f

/** Protein in grams per 100 units of product, bounded by [[Constants.MaxNutrientPer100]]. */
opaque type Protein = Int

object Protein:
  def from(v: Int): Either[DomainError, Protein] = Validation.nonNegative(v, Constants.MaxNutrientPer100)
  extension (p: Protein) def value: Int = p

/** Aggregated nutritional data of a product, expressed per 100 units of the product. */
final case class Product(
    id: ProductId,
    userId: UserId,
    name: ProductName,
    category: Option[CategoryName],
    unit: ProductUnit,
    caloriesPer100: Calories,
    proteinPer100: Protein,
    fatPer100: Fat,
    carbsPer100: Carbs,
    isArchived: Boolean = false,
    createdAt: Instant = Instant.now(),
    updatedAt: Instant = Instant.now()
)
