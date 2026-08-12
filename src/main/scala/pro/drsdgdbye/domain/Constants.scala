package pro.drsdgdbye.domain

/** Centralized domain limits and defaults. */
object Constants:
  val MaxNameLength: Int = 200
  val MaxCategoryLength: Int = 100
  val MaxNutrientPer100: Int = 1000
  val MaxQuantity: Int = 10000
  val MaxCookedWeightGrams: Int = 50000
  val DefaultPageLimit: Int = 20
  val MaxPageLimit: Int = 100
  val DefaultUserId: Long = 1L
  val DefaultUsername: String = "default"
