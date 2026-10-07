package br.com.walletscheduler.adapter.`in`.rest

import br.com.walletscheduler.application.port.DependencyUnavailableException
import br.com.walletscheduler.domain.BusinessRuleException
import br.com.walletscheduler.domain.ConflictException
import br.com.walletscheduler.domain.DomainException
import br.com.walletscheduler.domain.NotFoundException
import br.com.walletscheduler.domain.ValidationException
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException

/** problem+json with a stable `code`, the same shape wallet-core answers with. */
@RestControllerAdvice
class ApiExceptionHandler {

    private val log = LoggerFactory.getLogger(javaClass)

    @ExceptionHandler(DomainException::class)
    fun domain(e: DomainException): ResponseEntity<ProblemDetail> {
        // An exhaustive "when" over the sealed hierarchy: a new kind of DomainException will not compile until it is mapped here.
        val status = when (e) {
            is ValidationException -> HttpStatus.BAD_REQUEST
            is NotFoundException -> HttpStatus.NOT_FOUND
            is ConflictException -> HttpStatus.CONFLICT
            is BusinessRuleException -> HttpStatus.UNPROCESSABLE_ENTITY
        }
        return problem(status, e.code, e.message)
    }

    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun invalid(e: MethodArgumentNotValidException): ResponseEntity<ProblemDetail> =
        problem(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED",
            e.bindingResult.fieldErrors.joinToString("; ") { "${it.field}: ${it.defaultMessage}" })

    @ExceptionHandler(HttpMessageNotReadableException::class, MethodArgumentTypeMismatchException::class)
    fun malformed(e: Exception): ResponseEntity<ProblemDetail> =
        problem(HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST", "request could not be parsed")

    @ExceptionHandler(ArithmeticException::class)
    fun amount(e: ArithmeticException): ResponseEntity<ProblemDetail> =
        problem(HttpStatus.BAD_REQUEST, "INVALID_AMOUNT", "amount must have at most 2 decimal places")

    @ExceptionHandler(DependencyUnavailableException::class)
    fun unavailable(e: DependencyUnavailableException): ResponseEntity<ProblemDetail> {
        log.warn("dependency unavailable: {}", e.message)
        return problem(HttpStatus.SERVICE_UNAVAILABLE, "TEMPORARILY_UNAVAILABLE", "try again in a moment")
    }

    private fun problem(status: HttpStatus, code: String, detail: String?): ResponseEntity<ProblemDetail> {
        val body = ProblemDetail.forStatusAndDetail(status, detail ?: status.reasonPhrase)
        body.setProperty("code", code)
        return ResponseEntity.status(status).body(body)
    }
}
