package br.com.walletapp.api.adapter.`in`.rest

import br.com.walletapp.api.application.ScheduleService
import br.com.walletapp.api.domain.LoginId
import br.com.walletapp.contract.Schedule
import br.com.walletapp.contract.ScheduleRequest
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/** The logged-in customer's schedules. The payer account comes from the token; no schedule of another account is reachable. */
@RestController
@RequestMapping("/app/v1/schedules")
class ScheduleController(private val schedules: ScheduleService) {

    private val log = LoggerFactory.getLogger(javaClass)

    @GetMapping
    fun list(@AuthenticationPrincipal jwt: Jwt): List<Schedule> = asCustomer(jwt) { schedules.list(it) }

    @GetMapping("/{id}")
    fun get(@AuthenticationPrincipal jwt: Jwt, @PathVariable id: String): Schedule = asCustomer(jwt) { schedules.get(it, id) }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun create(
        @AuthenticationPrincipal jwt: Jwt,
        @RequestHeader("Idempotency-Key", required = false) key: String?,
        @RequestBody r: ScheduleRequest,
    ): Schedule = asCustomer(jwt) { account ->
        schedules.create(LoginId(UUID.fromString(jwt.subject)), account, r, key)
            .also { log.info("schedule created: id={} type={} executeOn={}", it.id, it.type, it.executeOn) }
    }

    @PostMapping("/{id}/cancel")
    fun cancel(@AuthenticationPrincipal jwt: Jwt, @PathVariable id: String): Schedule = asCustomer(jwt) { account ->
        schedules.cancel(account, id).also { log.info("schedule cancelled: id={}", id) }
    }
}
