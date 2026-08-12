package pro.drsdgdbye.domain

import java.time.Instant

/** Identifier of a dish. */
opaque type DishId = Long

object DishId:
  def from(id: Long): Either[DomainError, DishId] = Validation.positiveId(id)
  extension (id: DishId) def value: Long = id

/** Non-empty trimmed dish name, max [[Constants.MaxNameLength]] characters. */
opaque type DishName = String

object DishName:
  def from(s: String): Either[DomainError, DishName] = Validation.trimmed(s, Constants.MaxNameLength)
  extension (n: DishName) def value: String = n

/** Cooked weight of a dish in grams, bounded by [[Constants.MaxCookedWeightGrams]]. */
opaque type Weight = Int

object Weight:
  def from(v: Int): Either[DomainError, Weight] = Validation.positive(v, Constants.MaxCookedWeightGrams)
  extension (w: Weight) def value: Int = w

/** A dish prepared from several ingredients, described by its cooked weight. */
final case class Dish(
    id: DishId,
    userId: UserId,
    name: DishName,
    cookedWeightGrams: Weight,
    createdAt: Instant = Instant.now(),
    updatedAt: Instant = Instant.now()
)
