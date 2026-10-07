package br.com.walletscheduler.application.port

import br.com.walletscheduler.domain.AccountId
import br.com.walletscheduler.domain.Attempt
import br.com.walletscheduler.domain.Execution
import br.com.walletscheduler.domain.ExecutionId
import br.com.walletscheduler.domain.PixDestination
import br.com.walletscheduler.domain.Schedule
import br.com.walletscheduler.domain.ScheduleId
import br.com.walletscheduler.domain.TenantId
import java.time.Instant
import java.util.UUID

/** wallet-core, called as the tenant that owns the schedule ([clientId] picks its credentials). */
interface WalletCore {

    /** Whether this service has credentials for the tenant: without them nothing can run on the day. */
    fun serves(clientId: String): Boolean

    fun findAccount(clientId: String, accountId: AccountId): CoreAccount?

    fun findAccountByNumber(clientId: String, branch: String, number: String, checkDigit: String): CoreAccount?

    /**
     * Whether [taxId] is the holder of the account (wallet-core's holder-check): VALID, TAX_ID_MISMATCH,
     * ACCOUNT_NOT_FOUND, ACCOUNT_BLOCKED or ACCOUNT_CLOSED.
     */
    fun checkHolder(clientId: String, account: CoreAccount, taxId: String): String

    /** Idempotent by [idempotencyKey]: the same key returns the original transfer instead of a new one. */
    fun transfer(clientId: String, from: AccountId, to: AccountId, amountCents: Long, description: String?,
                 idempotencyKey: String): PaymentResult
}

/** wallet-pix's send API, called as the tenant that owns the schedule. */
interface PixPayments {

    /**
     * Idempotent by [idempotencyKey]. A repeated key does not send again: it returns the payment as it
     * is now - which is how a lost result event is recovered.
     */
    fun send(clientId: String, payerAccountId: AccountId, payerTaxId: String, payee: PixDestination,
             amountCents: Long, description: String?, idempotencyKey: String): PaymentResult
}

data class CoreAccount(
    val id: AccountId,
    val branch: String,
    val number: String,
    val checkDigit: String,
    val status: String,
    val holderName: String,
) {
    val active: Boolean get() = status == "ACTIVE"
}

/** The answers an attempt can get (ADR-001, decision 6). */
sealed interface PaymentResult {
    /**
     * Money moved. [endToEndId] only for a Pix. [transactionId] is null only for a Pix settled by its
     * event, which does not carry the debit: the attempt already holds it.
     */
    data class Executed(val transactionId: UUID?, val endToEndId: String? = null) : PaymentResult

    /** A Pix the Pix service took and sent: the payer was debited ([transactionId]), settlement comes later. */
    data class Pending(val endToEndId: String, val transactionId: UUID?) : PaymentResult

    /** Refused with a stable code (INSUFFICIENT_FUNDS, AC03...). Nothing moved, or it was moved back. */
    data class Refused(val code: String, val detail: String?) : PaymentResult

    /** Timeout, 5xx, network: the payment may or may not have happened. */
    data class Unknown(val cause: String) : PaymentResult
}

/** What a Pix result event tells: [requestId] is the Idempotency-Key the attempt sent the Pix with. */
data class PixOutcome(val requestId: String, val settled: Boolean, val reasonCode: String?)

/**
 * The event got here before the attempt recorded the Pix service's answer (both are fast): it must be
 * handled again a little later, when the attempt knows its EndToEndId.
 */
class NotReadyYetException(message: String) : RuntimeException(message)

/** A dependency did not answer while serving an API request; the caller may try again. */
class DependencyUnavailableException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

interface Transactions {
    fun <T> inTransaction(work: () -> T): T
}

/** A schedule with everything the screen shows about it. */
data class ScheduleDetails(val schedule: Schedule, val execution: Execution, val attempts: List<Attempt>)

data class StoredRequest(val scheduleId: ScheduleId, val fingerprint: String)

interface ScheduleRepository {

    /** @return false when the tenant already used [idempotencyKey] (nothing was written) */
    fun insert(schedule: Schedule, execution: Execution, idempotencyKey: String, fingerprint: String): Boolean

    fun findRequest(tenantId: TenantId, idempotencyKey: String): StoredRequest?

    fun find(tenantId: TenantId, id: ScheduleId): ScheduleDetails?

    /** Newest payment date first. */
    fun listByPayer(tenantId: TenantId, payerAccountId: AccountId): List<ScheduleDetails>

    /** Locks the schedule and its execution until the end of the transaction. */
    fun lock(tenantId: TenantId, id: ScheduleId): Pair<Schedule, Execution>?

    /**
     * Locks, until the end of the transaction, up to [limit] executions of any tenant that need the
     * job: PENDING with the attempt due, or PROCESSING with the lease expired. Rows another worker
     * holds are skipped (FOR UPDATE SKIP LOCKED), so several instances never take the same one.
     */
    fun lockDueExecutions(now: Instant, limit: Int): List<Execution>

    fun findSchedule(id: ScheduleId): Schedule

    fun findOpenAttempt(executionId: ExecutionId): Attempt?

    fun findAttemptByKey(idempotencyKey: String): Attempt?

    fun findExecution(id: ExecutionId): Execution

    fun update(schedule: Schedule)

    fun update(execution: Execution)

    fun insert(attempt: Attempt)

    fun update(attempt: Attempt)
}
