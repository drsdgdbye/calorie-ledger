package pro.drsdgdbye.calculation

/** Nutritional snapshot of a single ingredient, values per 100 units of the product. */
final case class IngredientNutrients(
    quantity: Int,
    caloriesPer100: Int,
    proteinPer100: Int,
    fatPer100: Int,
    carbsPer100: Int
)

/** Pure functions computing dish nutrition from its ingredients; no I/O, easily unit-tested. */
object NutrientCalculation:

  /** Total nutrients of a dish (or per its cooked weight) after rounding. */
  final case class Nutrients(calories: Int, protein: Int, fat: Int, carbs: Int)

  /** Sums raw nutrient units across ingredients (grams of product x per-100 values), then converts to whole nutrients
    * by dividing by 100 and rounding half-up.
    */
  def totals(ingredients: Seq[IngredientNutrients]): Nutrients =
    val (c, p, f, cb) = sums(ingredients)
    Nutrients(roundHalfUp(c / 100.0), roundHalfUp(p / 100.0), roundHalfUp(f / 100.0), roundHalfUp(cb / 100.0))

  /** Sums raw nutrient units across ingredients and normalizes them to the cooked weight of the dish, rounding half-up.
    * Returns zero nutrients when the cooked weight is not positive.
    */
  def per100(ingredients: Seq[IngredientNutrients], cookedWeightGrams: Int): Nutrients =
    if cookedWeightGrams <= 0 then Nutrients(0, 0, 0, 0)
    else
      val (c, p, f, cb) = sums(ingredients)
      Nutrients(
        roundHalfUp(c / cookedWeightGrams.toDouble),
        roundHalfUp(p / cookedWeightGrams.toDouble),
        roundHalfUp(f / cookedWeightGrams.toDouble),
        roundHalfUp(cb / cookedWeightGrams.toDouble)
      )

  /** Aggregates each nutrient as quantity x per-100 value, in Long to avoid overflow. */
  private def sums(ingredients: Seq[IngredientNutrients]): (Long, Long, Long, Long) =
    ingredients.foldLeft((0L, 0L, 0L, 0L)) { case ((tc, tp, tf, tcb), i) =>
      (
        tc + i.quantity.toLong * i.caloriesPer100,
        tp + i.quantity.toLong * i.proteinPer100,
        tf + i.quantity.toLong * i.fatPer100,
        tcb + i.quantity.toLong * i.carbsPer100
      )
    }

  /** Rounds half away from zero; only rounding point in the whole calculation. */
  private def roundHalfUp(v: Double): Int = Math.round(v).toInt
