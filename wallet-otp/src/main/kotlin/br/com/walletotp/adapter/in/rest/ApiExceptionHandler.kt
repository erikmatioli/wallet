package br.com.walletotp.adapter.`in`.rest

import br.com.walletotp.application.CodeNotSentException
import br.com.walletotp.domain.BusinessRuleException
import br.com.walletotp.domain.DomainException
import br.com.walletotp.domain.NotFoundException
import br.com.walletotp.domain.TooManyRequestsException
import br.com.walletotp.domain.ValidationException
import org.slf4j.LoggerFactory
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

/** problem+json with a stable `code`, the same shape wallet-core answers with. */
@RestControllerAdvice
class ApiExceptionHandler {

    private val log = LoggerFactory.getLogger(javaClass)

    @ExceptionHandler(DomainException::class)
    fun domain(e: DomainException): ResponseEntity<ProblemDetail> {
        // Exhaustive over the sealed hierarchy: a new kind of DomainException will not compile until it is mapped here.
        val status = when (e) {
            is ValidationException -> HttpStatus.BAD_REQUEST
            is NotFoundException -> HttpStatus.NOT_FOUND
            is BusinessRuleException -> HttpStatus.UNPROCESSABLE_ENTITY
            is TooManyRequestsException -> HttpStatus.TOO_MANY_REQUESTS
        }
        val response = problem(status, e.code, e.message)
        when (e) {
            is BusinessRuleException -> e.attemptsLeft?.let { response.body!!.setProperty("attemptsLeft", it) }
            is TooManyRequestsException -> {
                response.body!!.setProperty("retryAfterSeconds", e.retryAfterSeconds)
                return ResponseEntity.status(status).header(HttpHeaders.RETRY_AFTER, e.retryAfterSeconds.toString())
                    .body(response.body)
            }
            else -> {}
        }
        return response
    }

    @ExceptionHandler(HttpMessageNotReadableException::class)
    fun malformed(e: HttpMessageNotReadableException): ResponseEntity<ProblemDetail> =
        problem(HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST", "request could not be parsed")

    /** The email could not be sent: nothing reached the customer, they ask again (ADR-001, decision 6). */
    @ExceptionHandler(CodeNotSentException::class)
    fun notSent(e: CodeNotSentException): ResponseEntity<ProblemDetail> {
        log.warn("code not sent: {}", e.cause?.toString())
        return problem(HttpStatus.SERVICE_UNAVAILABLE, "CODE_NOT_SENT", "the code could not be sent, try again in a moment")
    }

    private fun problem(status: HttpStatus, code: String, detail: String?): ResponseEntity<ProblemDetail> {
        val body = ProblemDetail.forStatusAndDetail(status, detail ?: status.reasonPhrase)
        body.setProperty("code", code)
        return ResponseEntity.status(status).body(body)
    }
}
