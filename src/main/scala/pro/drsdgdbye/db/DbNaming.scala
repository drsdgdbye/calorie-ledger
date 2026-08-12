package pro.drsdgdbye.db

import io.getquill.NamingStrategy

/** Maps camelCase identifiers to snake_case, inserting an underscore before a digit that follows a letter (e.g.
  * caloriesPer100 -> calories_per_100), matching the physical column names in the database schema.
  */
object DbNaming extends NamingStrategy:

  def default(s: String): String =
    val builder = new StringBuilder
    s.foreach { c =>
      if c.isUpper then
        builder += '_'
        builder += c.toLower
      else if c.isDigit && builder.nonEmpty && builder.last.isLetter then
        builder += '_'
        builder += c
      else builder += c
    }
    builder.result()
