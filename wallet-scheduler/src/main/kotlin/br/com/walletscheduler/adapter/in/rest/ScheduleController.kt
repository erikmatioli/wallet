package br.com.walletscheduler.adapter.`in`.rest

import br.com.walletscheduler.application.ScheduleService
import br.com.walletscheduler.domain.AccountId
import br.com.walletscheduler.domain.PixDestination
import br.com.walletscheduler.domain.ScheduleId
import br.com.walletscheduler.domain.TenantId
import br.com.walletscheduler.domain.ValidationException
import jakarta.validation.Valid
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * Schedules of the tenant in the token. The tenant comes from the token (claim tenant_id, and its
 * client id in sub), never from the body - same rule as wallet-core and wallet-pix.
 */
@RestController
@RequestMapping("/v1/schedules")
class ScheduleController(private val schedules: ScheduleService) {

    private val log = LoggerFactory.getLogger(javaClass)

    @PostMapping
    fun create(
        @AuthenticationPrincipal jwt: Jwt,
        @RequestHeader("Idempotency-Key", required = false) idempotencyKey: String?,
        @Valid @RequestBody request: CreateScheduleRequest,
    ): ResponseEntity<ScheduleResponse> = asCaller(jwt) { caller ->
        // @Valid already refused nulls in the fields it checks, so the !! below cannot fail.
        val common = ScheduleService.Common(
            tenantId = caller.tenantId,
            clientId = caller.clientId,
            payerAccountId = AccountId(request.payerAccountId!!),
            amountCents = request.amount!!.movePointRight(2).longValueExact(),
            description = request.description,
            executeOn = request.executeOn!!,
            idempotencyKey = idempotencyKey,
        )
        val created = when (request.type ?: "TRANSFER") {
            "PIX" -> {
                val pix = request.pix?.takeIf { request.destination == null }
                    ?: throw ValidationException("INVALID_REQUEST", "a PIX schedule needs \"pix\" and no \"destination\"")
                val payee = pix.payee!!
                schedules.createPix(ScheduleService.CreatePix(common, pix.payerTaxId!!.filter(Char::isLetterOrDigit),
                    PixDestination(payee.ispb!!, payee.branch!!, payee.accountNumber!!,
                        payee.taxId!!.filter(Char::isLetterOrDigit), payee.name!!.trim())))
            }
            else -> {
                val destination = request.destination?.takeIf { request.pix == null }
                    ?: throw ValidationException("INVALID_REQUEST", "a TRANSFER schedule needs \"destination\" and no \"pix\"")
                schedules.createTransfer(ScheduleService.CreateTransfer(common, destination.branch!!,
                    destination.number!!, destination.checkDigit!!))
            }
        }
        val s = created.details.schedule
        log.info("schedule {}: id={} type={} executeOn={} amount={}", if (created.replayed) "replayed" else "created",
            s.id, s.type, s.executeOn, request.amount)
        ResponseEntity.status(if (created.replayed) HttpStatus.OK else HttpStatus.CREATED)
            .body(ScheduleResponse.from(created.details))
    }

    @GetMapping
    fun list(@AuthenticationPrincipal jwt: Jwt, @RequestParam payerAccountId: UUID): List<ScheduleResponse> =
        asCaller(jwt) { schedules.listByPayer(it.tenantId, AccountId(payerAccountId)).map(ScheduleResponse::from) }

    @GetMapping("/{id}")
    fun get(@AuthenticationPrincipal jwt: Jwt, @PathVariable id: UUID): ScheduleResponse =
        asCaller(jwt) { ScheduleResponse.from(schedules.get(it.tenantId, ScheduleId(id))) }

    @PostMapping("/{id}/cancel")
    fun cancel(@AuthenticationPrincipal jwt: Jwt, @PathVariable id: UUID): ScheduleResponse = asCaller(jwt) {
        ScheduleResponse.from(schedules.cancel(it.tenantId, ScheduleId(id))).also { log.info("schedule cancelled: id={}", id) }
    }

    /** Who is calling: the tenant (claim tenant_id) and its wallet-core client id (sub). */
    private data class Caller(val tenantId: TenantId, val clientId: String)

    /** Runs [block] as the token's caller, with tenant_id in the logging MDC for every line it writes. */
    private fun <T> asCaller(jwt: Jwt, block: (Caller) -> T): T {
        // Both are always in wallet-core's tokens; a token without them is not one of ours.
        val tenant = jwt.getClaimAsString("tenant_id") ?: throw IllegalStateException("token without tenant_id")
        val clientId = jwt.subject ?: throw IllegalStateException("token without subject")
        val caller = Caller(TenantId(UUID.fromString(tenant)), clientId)
        return MDC.putCloseable("tenant_id", tenant).use { block(caller) }
    }
}
