package br.com.walletapp.api.domain

/**
 * Errors with a stable [code] and a [message] written for the customer: the desktop shows it as it is.
 * The REST adapter maps each kind to an HTTP status (sealed: a new kind must be mapped to compile).
 */
sealed class DomainException(val code: String, message: String) : RuntimeException(message)

/** Invalid input: 400. */
class ValidationException(code: String, message: String) : DomainException(code, message)

/** Wrong or unknown login code, expired or invalid session: 401. */
class AuthenticationException(code: String, message: String) : DomainException(code, message)

/** Not found, or not this customer's: 404. */
class NotFoundException(code: String, message: String) : DomainException(code, message)

/** Already exists: 409. */
class ConflictException(code: String, message: String) : DomainException(code, message)

/** Valid request refused by a rule (balance, expired code...): 422. */
class BusinessRuleException(code: String, message: String) : DomainException(code, message)

/** Too many codes asked for (ADR-002): 429, with how long until another one may be asked. */
class TooManyRequestsException(message: String, val retryAfterSeconds: Long) : DomainException("TOO_MANY_REQUESTS", message)
