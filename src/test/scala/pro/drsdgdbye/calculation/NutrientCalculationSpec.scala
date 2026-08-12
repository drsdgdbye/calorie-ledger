package pro.drsdgdbye.calculation

import zio.test.*

object NutrientCalculationSpec extends ZIOSpecDefault:

  private def ing(quantity: Int, calories: Int, protein: Int = 0, fat: Int = 0, carbs: Int = 0): IngredientNutrients =
    IngredientNutrients(quantity, calories, protein, fat, carbs)

  def spec: Spec[Any, Nothing] =
    suite("NutrientCalculation")(
      suite("totals")(
        test("empty ingredient list yields zero nutrients") {
          assertTrue(NutrientCalculation.totals(Vector.empty) == NutrientCalculation.Nutrients(0, 0, 0, 0))
        },
        test("single ingredient computes quantity x per-100 divided by 100") {
          assertTrue(
            NutrientCalculation.totals(Vector(ing(300, 330, 7, 1, 74))) ==
              NutrientCalculation.Nutrients(990, 21, 3, 222)
          )
        },
        test("multiple ingredients are summed per nutrient") {
          assertTrue(
            NutrientCalculation.totals(Vector(ing(200, 330, 7, 1, 74), ing(100, 120, 3, 4, 5))) ==
              NutrientCalculation.Nutrients(780, 17, 6, 153)
          )
        },
        test("rounds half up at the final step only") {
          assertTrue(NutrientCalculation.totals(Vector(ing(15, 10))) == NutrientCalculation.Nutrients(2, 0, 0, 0))
        },
        test("rounds every nutrient half up independently") {
          assertTrue(
            NutrientCalculation.totals(Vector(ing(1, 150, 150, 150, 150))) ==
              NutrientCalculation.Nutrients(2, 2, 2, 2)
          )
        },
        test("sub-half values round down") {
          assertTrue(NutrientCalculation.totals(Vector(ing(1, 149))) == NutrientCalculation.Nutrients(1, 0, 0, 0))
        },
        test("large quantities do not overflow") {
          assertTrue(
            NutrientCalculation.totals(Vector(ing(10000, 1000, 1000, 1000, 1000))) ==
              NutrientCalculation.Nutrients(100000, 100000, 100000, 100000)
          )
        }
      ),
      suite("per100")(
        test("normalizes totals to the cooked weight") {
          assertTrue(
            NutrientCalculation.per100(Vector(ing(300, 330), ing(100, 120)), 1200) ==
              NutrientCalculation.Nutrients(93, 0, 0, 0)
          )
        },
        test("returns zero nutrients for zero cooked weight") {
          assertTrue(NutrientCalculation.per100(Vector(ing(300, 330)), 0) == NutrientCalculation.Nutrients(0, 0, 0, 0))
        },
        test("returns zero nutrients for negative cooked weight") {
          assertTrue(
            NutrientCalculation.per100(Vector(ing(300, 330)), -100) == NutrientCalculation.Nutrients(0, 0, 0, 0)
          )
        },
        test("rounds half up when normalizing") {
          assertTrue(
            NutrientCalculation.per100(Vector(ing(1, 250)), 4) == NutrientCalculation.Nutrients(63, 0, 0, 0)
          )
        },
        test("rounds each nutrient half up independently") {
          assertTrue(
            NutrientCalculation.per100(Vector(IngredientNutrients(1, 250, 250, 250, 250)), 4) ==
              NutrientCalculation.Nutrients(63, 63, 63, 63)
          )
        },
        test("empty ingredients with a positive weight yield zero") {
          assertTrue(NutrientCalculation.per100(Vector.empty, 100) == NutrientCalculation.Nutrients(0, 0, 0, 0))
        }
      )
    )
