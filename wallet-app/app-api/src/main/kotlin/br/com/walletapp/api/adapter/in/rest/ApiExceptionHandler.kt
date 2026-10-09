package br.com.walletapp.api.adapter.`in`.rest

import br.com.walletapp.api.application.port.DependencyUnavailableException
import br.com.walletapp.api.domain.AuthenticationException
import br.com.walletapp.api.domain.BusinessRuleException
import br.com.walletapp.api.domain.ConflictException
import br.com.walletapp.api.domain.DomainException
import br.com.walletapp.api.domain.NotFoundException
import br.com.walletapp.api.domain.TooManyRequestsException
import br.com.walletapp.api.domain.ValidationException
import br.com.walletapp.contract.AppError
import org.slf4j.LoggerFactory
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

/** Every error as the contract's [AppError]: a stable code and a message the desktop shows as it is. */
@RestControllerAdvice
class ApiExceptionHandler {

    private val log = LoggerFactory.getLogger(javaClass)

    @ExceptionHandler(DomainException::class)
    fun domain(e: DomainException): ResponseEntity<AppError> {
        val status = when (e) {
            is ValidationException -> HttpStatus.BAD_REQUEST
            is AuthenticationException -> HttpStatus.UNAUTHORIZED
            is NotFoundException -> HttpStatus.NOT_FOUND
            is ConflictException -> HttpStatus.CONFLICT
            is BusinessRuleException -> HttpStatus.UNPROCESSABLE_ENTITY
            is TooManyRequestsException -> return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header(HttpHeaders.RETRY_AFTER, e.retryAfterSeconds.toString())
                .body(AppError(e.code, e.message ?: e.code, e.retryAfterSeconds))
        }
        return ResponseEntity.status(status).body(AppError(e.code, e.message ?: e.code))
    }

    @ExceptionHandler(HttpMessageNotReadableException::class)
    fun malformed(e: HttpMessageNotReadableException): ResponseEntity<AppError> =
        ResponseEntity.badRequest().body(AppError("MALFORMED_REQUEST", "Dados inválidos."))

    @ExceptionHandler(DependencyUnavailableException::class)
    fun unavailable(e: DependencyUnavailableException): ResponseEntity<AppError> {
        log.warn("dependency unavailable: {}", e.message)
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
            .body(AppError("TEMPORARILY_UNAVAILABLE", "Serviço indisponível no momento. Tente de novo."))
    }
}
