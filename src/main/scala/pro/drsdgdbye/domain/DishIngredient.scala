package pro.drsdgdbye.domain

/** Identifier of a dish ingredient row. */
opaque type DishIngredientId = Long

object DishIngredientId:
  def from(id: Long): Either[DomainError, DishIngredientId] = Validation.positiveId(id)
  extension (id: DishIngredientId) def value: Long = id

/** Amount of a product used in a dish, bounded by [[Constants.MaxQuantity]]. */
opaque type Quantity = Int

object Quantity:
  def from(v: Int): Either[DomainError, Quantity] = Validation.positive(v, Constants.MaxQuantity)
  extension (q: Quantity) def value: Int = q

/** Ordering position of an ingredient within a dish. */
opaque type Position = Int

object Position:
  def from(v: Int): Either[DomainError, Position] = Validation.nonNegative(v, Int.MaxValue)
  extension (p: Position) def value: Int = p

/** A single product used in a dish, with its amount and ordering position. */
final case class DishIngredient(
    id: DishIngredientId,
    dishId: DishId,
    productId: ProductId,
    quantity: Quantity,
    position: Position
)
