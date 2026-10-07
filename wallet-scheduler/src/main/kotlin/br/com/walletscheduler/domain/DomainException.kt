package br.com.walletscheduler.domain

/**
 * Business errors with a stable [code], which the REST adapter turns into the HTTP status and the
 * problem body - same idea as wallet-core's DomainException hierarchy, as a sealed class.
 */
sealed class DomainException(val code: String, message: String) : RuntimeException(message)

/** Malformed or invalid input: 400. */
class ValidationException(code: String, message: String) : DomainException(code, message)

/** 404. */
class NotFoundException(code: String, message: String) : DomainException(code, message)

/** Same Idempotency-Key, different request: 409. */
class ConflictException(code: String, message: String) : DomainException(code, message)

/** A valid request that a business rule refuses (e.g. cancelling on the day): 422. */
class BusinessRuleException(code: String, message: String) : DomainException(code, message)
