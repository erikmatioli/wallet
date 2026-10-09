package br.com.walletotp.domain

/** Errors with a stable [code], which the REST adapter turns into the HTTP status and the problem body. */
sealed class DomainException(val code: String, message: String) : RuntimeException(message)

/** Malformed input: 400. */
class ValidationException(code: String, message: String) : DomainException(code, message)

/** 404. Also when the subject or the context do not match: the caller is not told which one. */
class NotFoundException(code: String, message: String) : DomainException(code, message)

/** A valid request a rule refuses (wrong code, expired, locked...): 422. [attemptsLeft] only for a wrong code. */
class BusinessRuleException(code: String, message: String, val attemptsLeft: Int? = null) : DomainException(code, message)

/** Too many codes asked for (ADR-001, decision 5): 429, with how long to wait. */
class TooManyRequestsException(message: String, val retryAfterSeconds: Long) :
    DomainException("TOO_MANY_REQUESTS", message)
