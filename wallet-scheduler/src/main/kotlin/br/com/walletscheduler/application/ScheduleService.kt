package br.com.walletscheduler.application

import br.com.walletscheduler.application.port.CoreAccount
import br.com.walletscheduler.application.port.ScheduleDetails
import br.com.walletscheduler.application.port.ScheduleRepository
import br.com.walletscheduler.application.port.Transactions
import br.com.walletscheduler.application.port.WalletCore
import br.com.walletscheduler.domain.AccountId
import br.com.walletscheduler.domain.BusinessRuleException
import br.com.walletscheduler.domain.ConflictException
import br.com.walletscheduler.domain.Destination
import br.com.walletscheduler.domain.ExecutionWindows
import br.com.walletscheduler.domain.NotFoundException
import br.com.walletscheduler.domain.PixDestination
import br.com.walletscheduler.domain.Schedule
import br.com.walletscheduler.domain.ScheduleId
import br.com.walletscheduler.domain.TenantId
import br.com.walletscheduler.domain.TransferDestination
import br.com.walletscheduler.domain.ValidationException
import java.security.MessageDigest
import java.time.Clock
import java.time.LocalDate
import java.util.HexFormat

/** Creating, reading and cancelling schedules - everything the API does. Running them is [ExecutionService]. */
class ScheduleService(
    private val walletCore: WalletCore,
    private val schedules: ScheduleRepository,
    private val tx: Transactions,
    private val windows: ExecutionWindows,
    private val clock: Clock,
) {

    /** What every schedule has, whatever it pays. */
    data class Common(
        val tenantId: TenantId,
        val clientId: String,
        val payerAccountId: AccountId,
        val amountCents: Long,
        val description: String?,
        val executeOn: LocalDate,
        val idempotencyKey: String?,
    )

    data class CreateTransfer(
        val common: Common,
        val destinationBranch: String,
        val destinationNumber: String,
        val destinationCheckDigit: String,
    )

    /** [payee] is validated on construction (formats); [payerTaxId] is checked against the account holder here. */
    data class CreatePix(val common: Common, val payerTaxId: String, val payee: PixDestination)

    data class Created(val details: ScheduleDetails, val replayed: Boolean)

    /**
     * Validates what can be validated today (ADR-001, decision 4) and stores the schedule. Balance is
     * not checked: it only matters on the day. Idempotent by Idempotency-Key, like wallet-core.
     */
    fun createTransfer(c: CreateTransfer): Created =
        create(c.common, fingerprint("TRANSFER", c.destinationBranch, c.destinationNumber, c.destinationCheckDigit)) { _ ->
            val target = walletCore.findAccountByNumber(c.common.clientId, c.destinationBranch, c.destinationNumber,
                c.destinationCheckDigit)
                ?: throw NotFoundException("DESTINATION_ACCOUNT_NOT_FOUND", "destination account not found")
            null to TransferDestination(target.branch, target.number, target.checkDigit, target.id, target.holderName)
        }

    /**
     * Besides the common checks, the payer's CPF/CNPJ must be the account holder's: the Pix service
     * would refuse it on the day otherwise, and that is better found out now. The payee is only checked
     * for format - another institution's account cannot be looked up from here.
     */
    fun createPix(c: CreatePix): Created {
        val payee = c.payee
        val key = fingerprint("PIX", c.payerTaxId, payee.ispb, payee.branch, payee.accountNumber, payee.taxId,
            payee.holderName)
        return create(c.common, key) { payer ->
            val holder = walletCore.checkHolder(c.common.clientId, payer, c.payerTaxId)
            if (holder != "VALID") {
                throw BusinessRuleException("PAYER_$holder", "the payer's CPF/CNPJ cannot pay from this account ($holder)")
            }
            c.payerTaxId to payee
        }
    }

    private fun create(c: Common, typeFingerprint: String, destinationOf: (CoreAccount) -> Pair<String?, Destination>): Created {
        val key = c.idempotencyKey?.trim()?.takeIf { it.isNotEmpty() }
            ?: throw ValidationException("IDEMPOTENCY_KEY_REQUIRED", "Idempotency-Key is required")
        if (key.length > MAX_KEY) {
            throw ValidationException("INVALID_IDEMPOTENCY_KEY", "Idempotency-Key must have at most $MAX_KEY characters")
        }
        val fingerprint = fingerprint(typeFingerprint, c.payerAccountId, c.amountCents, c.description?.trim().orEmpty(),
            c.executeOn)

        // A replay is answered from our own database, without asking wallet-core again.
        replayOf(c.tenantId, key, fingerprint)?.let { return it }

        if (!walletCore.serves(c.clientId)) {
            throw BusinessRuleException("TENANT_NOT_CONFIGURED",
                "this service has no credentials for ${c.clientId}, so it could not pay on the day")
        }
        val payer = walletCore.findAccount(c.clientId, c.payerAccountId)
            ?: throw NotFoundException("PAYER_ACCOUNT_NOT_FOUND", "payer account not found")
        if (!payer.active) {
            throw BusinessRuleException("PAYER_ACCOUNT_NOT_ACTIVE", "payer account is ${payer.status}")
        }
        val (payerTaxId, destination) = destinationOf(payer)

        val (schedule, execution) = Schedule.create(c.tenantId, c.clientId, c.payerAccountId, payerTaxId, destination,
            c.amountCents, c.description, c.executeOn, clock.instant(), windows)
        val inserted = tx.inTransaction { schedules.insert(schedule, execution, key, fingerprint) }
        if (!inserted) {
            // Lost a race with a concurrent request carrying the same key: answer as that one.
            return replayOf(c.tenantId, key, fingerprint)
                ?: error("Idempotency-Key $key taken but no schedule found")
        }
        return Created(ScheduleDetails(schedule, execution, emptyList()), replayed = false)
    }

    fun get(tenantId: TenantId, id: ScheduleId): ScheduleDetails =
        schedules.find(tenantId, id) ?: throw NotFoundException("SCHEDULE_NOT_FOUND", "schedule not found")

    fun listByPayer(tenantId: TenantId, payerAccountId: AccountId): List<ScheduleDetails> =
        schedules.listByPayer(tenantId, payerAccountId)

    /** Until the day before (the rule lives in [Schedule.cancel]). */
    fun cancel(tenantId: TenantId, id: ScheduleId): ScheduleDetails {
        tx.inTransaction {
            val (schedule, execution) = schedules.lock(tenantId, id)
                ?: throw NotFoundException("SCHEDULE_NOT_FOUND", "schedule not found")
            val now = clock.instant()
            schedules.update(schedule.cancel(now, windows))
            schedules.update(execution.cancelled(now))
        }
        return get(tenantId, id)
    }

    private fun replayOf(tenantId: TenantId, key: String, fingerprint: String): Created? {
        val stored = schedules.findRequest(tenantId, key) ?: return null
        if (stored.fingerprint != fingerprint) {
            throw ConflictException("IDEMPOTENCY_KEY_REUSED", "this Idempotency-Key was already used with a different request")
        }
        return Created(get(tenantId, stored.scheduleId), replayed = true)
    }

    private companion object {
        const val MAX_KEY = 128

        fun fingerprint(vararg parts: Any?): String {
            val joined = parts.joinToString("\u001f")
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(joined.toByteArray()))
        }
    }
}
