package pro.drsdgdbye.domain

/** Shared boundary validators backing the domain smart constructors. */
private[domain] object Validation:
  def positive(v: Int, max: Int): Either[DomainError, Int] =
    Either.cond(v > 0 && v <= max, v, DomainError.ValidationError)

  def nonNegative(v: Int, max: Int): Either[DomainError, Int] =
    Either.cond(v >= 0 && v <= max, v, DomainError.ValidationError)

  def positiveId(v: Long): Either[DomainError, Long] =
    Either.cond(v > 0, v, DomainError.ValidationError)

  def trimmed(s: String, maxLen: Int): Either[DomainError, String] =
    val t = s.strip()
    Either.cond(t.nonEmpty && t.length <= maxLen, t, DomainError.ValidationError)
