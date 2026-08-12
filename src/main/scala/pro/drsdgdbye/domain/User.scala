package pro.drsdgdbye.domain

import java.time.Instant

/** Identifier of a user. */
opaque type UserId = Long

object UserId:
  def from(id: Long): Either[DomainError, UserId] = Validation.positiveId(id)
  def default: UserId = Constants.DefaultUserId
  extension (id: UserId) def value: Long = id

/** Non-empty trimmed username, max [[Constants.MaxNameLength]] characters. */
opaque type Username = String

object Username:
  def from(s: String): Either[DomainError, Username] = Validation.trimmed(s, Constants.MaxNameLength)
  extension (u: Username) def value: String = u

/** Application user owning products and dishes. */
final case class User(
    id: UserId,
    username: Username,
    createdAt: Instant = Instant.now()
)
