package br.com.walletotp.adapter.`in`.rest

import br.com.walletotp.application.ChallengeService
import br.com.walletotp.domain.Channel
import br.com.walletotp.domain.Purpose
import br.com.walletotp.domain.TenantId
import br.com.walletotp.domain.ValidationException
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import org.springframework.http.HttpStatus
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.time.Instant
import java.util.UUID

/** Challenges of the tenant in the token (ADR-001, decision 3). The tenant never comes from the body. */
@RestController
@RequestMapping("/v1/otp/challenges")
class ChallengeController(private val challenges: ChallengeService) {

    private val log = LoggerFactory.getLogger(javaClass)

    data class CreateRequest(val subject: String?, val purpose: String?, val channel: String?, val destination: String?,
                             val context: String?)

    data class CreateResponse(val challengeId: UUID, val expiresAt: Instant, val destinationMasked: String)

    data class VerifyRequest(val code: String?, val subject: String?, val context: String?)

    data class VerifyResponse(val verified: Boolean)

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun create(@AuthenticationPrincipal jwt: Jwt, @RequestBody r: CreateRequest): CreateResponse = asTenant(jwt) { tenant ->
        val purpose = enumOf<Purpose>(r.purpose, "INVALID_PURPOSE", "purpose must be one of ${Purpose.entries}")
        val channel = enumOf<Channel>(r.channel ?: "EMAIL", "INVALID_CHANNEL", "channel must be EMAIL")
        val created = challenges.create(ChallengeService.Create(tenant, r.subject, purpose, channel, r.destination, r.context))
        // No code, no subject, no email in the log: the id is enough to follow it.
        log.info("challenge {} sent: purpose={} to={}", created.id, purpose, created.destinationMasked)
        CreateResponse(created.id.value, created.expiresAt, created.destinationMasked)
    }

    @PostMapping("/{id}/verify")
    fun verify(@AuthenticationPrincipal jwt: Jwt, @PathVariable id: String, @RequestBody r: VerifyRequest): VerifyResponse =
        asTenant(jwt) { tenant ->
            challenges.verify(ChallengeService.Verify(tenant, id, r.subject, r.code, r.context))
            log.info("challenge {} verified", id)
            VerifyResponse(true)
        }

    private inline fun <reified E : Enum<E>> enumOf(raw: String?, code: String, message: String): E =
        enumValues<E>().firstOrNull { it.name == raw?.trim()?.uppercase() } ?: throw ValidationException(code, message)

    /** Runs [block] as the token's tenant, with tenant_id in the logging MDC for every line it writes. */
    private fun <T> asTenant(jwt: Jwt, block: (TenantId) -> T): T {
        val tenant = jwt.getClaimAsString("tenant_id") ?: throw IllegalStateException("token without tenant_id")
        return MDC.putCloseable("tenant_id", tenant).use { block(TenantId(UUID.fromString(tenant))) }
    }
}
