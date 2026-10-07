package br.com.walletscheduler.domain

import java.time.Instant
import java.time.LocalDate

enum class ScheduleType { TRANSFER, PIX }

enum class ScheduleStatus { ACTIVE, CANCELLED, COMPLETED }

/** Where the money goes. A sealed interface: every `when` over it must handle each kind, or not compile. */
sealed interface Destination {
    val type: ScheduleType

    /** Who receives, as the screen shows it. */
    val holderName: String
}

/**
 * A transfer destination as the customer typed it, plus what wallet-core told us about it when the
 * schedule was created: the account id (what the transfer is made to) and the holder's name.
 */
data class TransferDestination(
    val branch: String,
    val number: String,
    val checkDigit: String,
    val accountId: AccountId,
    override val holderName: String,
) : Destination {
    override val type get() = ScheduleType.TRANSFER
}

/**
 * A Pix payee, as the Pix service needs it on the day. [taxId] is kept in full because sending
 * requires it (ADR-001, decision 8); the API only ever shows [taxIdMasked].
 *
 * @property accountNumber the account number with its check digit, as the SPI carries it
 */
data class PixDestination(
    val ispb: String,
    val branch: String,
    val accountNumber: String,
    val taxId: String,
    override val holderName: String,
) : Destination {
    override val type get() = ScheduleType.PIX

    val taxIdMasked: String get() = "***" + taxId.takeLast(4)

    init {
        if (!ISPB.matches(ispb) || !BRANCH.matches(branch) || !ACCOUNT.matches(accountNumber)) {
            throw ValidationException("INVALID_PAYEE",
                "payee needs ispb (8 digits), branch (4) and account number with check digit (2 to 21 digits)")
        }
        if (!TAX_ID.matches(taxId)) {
            throw ValidationException("INVALID_PAYEE", "payee tax id must be a CPF (11 digits) or CNPJ (14)")
        }
        if (holderName.isBlank() || holderName.length > 140) {
            throw ValidationException("INVALID_PAYEE", "payee name is required (at most 140 characters)")
        }
    }

    private companion object {
        val ISPB = Regex("\\d{8}")
        val BRANCH = Regex("\\d{4}")
        val ACCOUNT = Regex("\\d{2,21}")
        val TAX_ID = Regex("\\d{11}|[0-9A-Z]{12}\\d{2}")
    }
}

/**
 * The customer's intent: pay [amountCents] from [payerAccountId] to [destination] on [executeOn].
 * Running it is the [Execution]'s job; this only knows whether it is still wanted.
 *
 * @property clientId     the tenant's wallet-core client id: on the day, the payment is made as that tenant
 * @property payerTaxId   the payer's CPF/CNPJ, only for Pix: the Pix service checks it against the holder
 */
data class Schedule(
    val id: ScheduleId,
    val tenantId: TenantId,
    val clientId: String,
    val payerAccountId: AccountId,
    val payerTaxId: String?,
    val destination: Destination,
    val amountCents: Long,
    val description: String?,
    val executeOn: LocalDate,
    val status: ScheduleStatus,
    val createdAt: Instant,
    val cancelledAt: Instant?,
) {
    val type: ScheduleType get() = destination.type

    init {
        require(amountCents > 0) { "amount must be positive" }
        require((destination is PixDestination) == (payerTaxId != null)) { "payerTaxId is for Pix schedules only" }
    }

    /** Until the day before, in Brasília (ADR-001, decision 2). On the day it is already running. */
    fun cancel(now: Instant, windows: ExecutionWindows): Schedule {
        if (status != ScheduleStatus.ACTIVE) {
            throw BusinessRuleException("SCHEDULE_NOT_ACTIVE", "only an active schedule can be cancelled (it is $status)")
        }
        if (!windows.today(now).isBefore(executeOn)) {
            throw BusinessRuleException("CANCELLATION_DEADLINE_PASSED",
                "a schedule can only be cancelled until the day before $executeOn")
        }
        return copy(status = ScheduleStatus.CANCELLED, cancelledAt = now)
    }

    fun completed(): Schedule = copy(status = ScheduleStatus.COMPLETED)

    companion object {
        private const val MAX_DESCRIPTION = 140
        private val TAX_ID = Regex("\\d{11}|[0-9A-Z]{12}\\d{2}")

        /** A new schedule and its execution, waiting for the first window of the day. */
        fun create(
            tenantId: TenantId,
            clientId: String,
            payerAccountId: AccountId,
            payerTaxId: String?,
            destination: Destination,
            amountCents: Long,
            description: String?,
            executeOn: LocalDate,
            now: Instant,
            windows: ExecutionWindows,
        ): Pair<Schedule, Execution> {
            if (amountCents <= 0) {
                throw ValidationException("INVALID_AMOUNT", "amount must be greater than zero")
            }
            if (!executeOn.isAfter(windows.today(now))) {
                throw ValidationException("INVALID_EXECUTION_DATE", "the payment date must be from tomorrow on")
            }
            if (executeOn.isAfter(windows.today(now).plusYears(1))) {
                throw ValidationException("INVALID_EXECUTION_DATE", "the payment date must be within one year")
            }
            val text = description?.trim()?.takeIf { it.isNotEmpty() }
            if (text != null && text.length > MAX_DESCRIPTION) {
                throw ValidationException("INVALID_DESCRIPTION", "description must have at most 140 characters")
            }
            // Exhaustive over the sealed Destination: a third kind will not compile until it is handled here.
            when (destination) {
                is TransferDestination -> if (destination.accountId == payerAccountId) {
                    throw ValidationException("SAME_ACCOUNT", "destination must be another account")
                }
                is PixDestination -> if (payerTaxId == null || !TAX_ID.matches(payerTaxId)) {
                    throw ValidationException("INVALID_PAYER_TAX_ID", "a Pix needs the payer's CPF (11 digits) or CNPJ (14)")
                }
            }
            val schedule = Schedule(ScheduleId.new(), tenantId, clientId, payerAccountId,
                payerTaxId.takeIf { destination is PixDestination }, destination, amountCents, text, executeOn,
                ScheduleStatus.ACTIVE, now, null)
            return schedule to Execution.pending(schedule, windows.first(executeOn), now)
        }
    }
}
