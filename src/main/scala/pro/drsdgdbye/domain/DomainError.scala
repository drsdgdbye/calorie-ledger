package pro.drsdgdbye.domain

/** Typed errors the domain can raise; single source of truth for the error channel. */
enum DomainError:
  case ProductNotFound
  case DishNotFound
  case ValidationError
  case InternalError
