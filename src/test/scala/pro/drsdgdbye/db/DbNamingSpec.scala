package pro.drsdgdbye.db

import zio.test.*

object DbNamingSpec extends ZIOSpecDefault:

  def spec: Spec[Any, Nothing] =
    suite("DbNaming")(
      test("converts camelCase to snake_case") {
        assertTrue(DbNaming.default("cookedWeightGrams") == "cooked_weight_grams")
      },
      test("separates digits from letters with an underscore") {
        assertTrue(DbNaming.default("caloriesPer100") == "calories_per_100")
      },
      test("keeps trailing digits without an extra underscore") {
        assertTrue(DbNaming.default("proteinPer100") == "protein_per_100")
      },
      test("leaves already snake_case names untouched") {
        assertTrue(DbNaming.default("user_id") == "user_id")
      }
    )
