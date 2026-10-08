package br.com.walletapp.api.adapter.`in`.rest

import br.com.walletapp.api.application.AccountService
import br.com.walletapp.api.application.AuthService
import br.com.walletapp.api.config.SessionClaims
import br.com.walletapp.api.domain.AccountId
import br.com.walletapp.contract.LoginRequest
import br.com.walletapp.contract.Me
import br.com.walletapp.contract.Session
import br.com.walletapp.contract.SignupRequest
import br.com.walletapp.contract.StatementPage
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import org.springframework.http.HttpStatus
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

// The request and response bodies are app-contract's @Serializable classes: Spring reads and writes
// them with kotlinx.serialization, the same library the desktop uses.

@RestController
@RequestMapping("/app/v1")
class AuthController(private val auth: AuthService) {

    private val log = LoggerFactory.getLogger(javaClass)

    @PostMapping("/signup")
    @ResponseStatus(HttpStatus.CREATED)
    fun signup(@RequestBody r: SignupRequest): Session = auth.signup(r.cpf, r.name, r.password)
        .also { log.info("customer signed up") } // no CPF, no name: the customer_id comes with the next calls

    @PostMapping("/login")
    fun login(@RequestBody r: LoginRequest): Session = auth.login(r.cpf, r.password)
}

/**
 * The logged-in customer's account. The account id is read from the token - there is no account
 * parameter a customer could change (ADR-001, decision 1).
 */
@RestController
@RequestMapping("/app/v1")
class AccountController(private val accounts: AccountService) {

    @GetMapping("/me")
    fun me(@AuthenticationPrincipal jwt: Jwt): Me = asCustomer(jwt) { accounts.me(it) }

    @GetMapping("/statement")
    fun statement(
        @AuthenticationPrincipal jwt: Jwt,
        @RequestParam(required = false) before: Long?,
        @RequestParam(defaultValue = "20") limit: Int,
    ): StatementPage = asCustomer(jwt) { accounts.statement(it, before, limit) }
}

/** Runs [block] with the token's account, and the customer (the login id) in the logging MDC. */
fun <T> asCustomer(jwt: Jwt, block: (AccountId) -> T): T {
    val account = jwt.getClaimAsString(SessionClaims.ACCOUNT_ID) ?: throw IllegalStateException("token without account")
    val customer = jwt.subject ?: throw IllegalStateException("token without subject")
    return MDC.putCloseable("customer_id", customer).use { block(AccountId(UUID.fromString(account))) }
}
