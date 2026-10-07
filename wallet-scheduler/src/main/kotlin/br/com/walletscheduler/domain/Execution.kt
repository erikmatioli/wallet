package br.com.walletscheduler.domain

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

@JvmInline
value class ExecutionId(val value: UUID) {
    override fun toString() = value.toString()

    companion object {
        fun new() = ExecutionId(UUID.randomUUID())
    }
}

enum class ExecutionStatus { PENDING, PROCESSING, EXECUTED, FAILED, CANCELLED }

/**
 * A schedule running on its day (ADR-001, decision 3). One per schedule for now; with recurrence,
 * one per occurrence.
 *
 * ```
 * PENDING --start--> PROCESSING --executed--> EXECUTED
 *    ^                    |  \---refused, no window left / missed day--> FAILED
 *    \--refused, next window--/
 * ```
 * An attempt whose result is unknown (timeout, 5xx) keeps the execution PROCESSING: when its
 * [leaseUntil] expires the job runs the same attempt again, with the same idempotency key.
 *
 * A Pix accepted by the Pix service is also PROCESSING ([awaitingResult]): the settlement arrives later
 * as an event. If the event never comes, the lease expires and the same attempt asks again - the Pix
 * service answers a repeated key with the payment as it is now.
 *
 * @property transactionId the wallet-core transaction that moved the money (for a Pix, its debit)
 * @property endToEndId    the Pix's EndToEndId, once the Pix service accepted it
 */
data class Execution(
    val id: ExecutionId,
    val scheduleId: ScheduleId,
    val tenantId: TenantId,
    val executeOn: LocalDate,
    val status: ExecutionStatus,
    val attemptCount: Int,
    val nextAttemptAt: Instant?,
    val leaseUntil: Instant?,
    val transactionId: UUID?,
    val endToEndId: String?,
    val failure: FailureReason?,
    val updatedAt: Instant,
) {
    val finished: Boolean
        get() = status == ExecutionStatus.EXECUTED || status == ExecutionStatus.FAILED ||
            status == ExecutionStatus.CANCELLED

    /** A new attempt: the number is what makes its idempotency key different from the previous one's. */
    fun startAttempt(now: Instant, lease: Duration): Pair<Execution, Attempt> {
        check(status == ExecutionStatus.PENDING) { "execution $id is $status, not PENDING" }
        val number = attemptCount + 1
        val started = copy(status = ExecutionStatus.PROCESSING, attemptCount = number, nextAttemptAt = null,
            leaseUntil = now.plus(lease), updatedAt = now)
        return started to Attempt(id, number, "sched-$id-$number", now, null, null, null, null, null)
    }

    /** Taking an attempt back after its lease expired: same attempt, same key. */
    fun resume(now: Instant, lease: Duration): Execution {
        check(status == ExecutionStatus.PROCESSING) { "execution $id is $status, not PROCESSING" }
        return copy(leaseUntil = now.plus(lease), updatedAt = now)
    }

    fun executed(transactionId: UUID, endToEndId: String?, now: Instant): Execution =
        copy(status = ExecutionStatus.EXECUTED, transactionId = transactionId, endToEndId = endToEndId ?: this.endToEndId,
            leaseUntil = null, failure = null, updatedAt = now)

    /** The Pix service took the Pix: wait for its result (an event), at most until [wait] runs out. */
    fun awaitingResult(endToEndId: String, transactionId: UUID?, now: Instant, wait: Duration): Execution =
        copy(endToEndId = endToEndId, transactionId = transactionId ?: this.transactionId, leaseUntil = now.plus(wait),
            updatedAt = now)

    /** Tries again in the next window of the day when the reason allows it; fails otherwise. */
    fun refused(reason: FailureReason, now: Instant, windows: ExecutionWindows): Execution {
        val next = if (reason.retryToday) windows.nextAfter(executeOn, now) else null
        return if (next != null) {
            copy(status = ExecutionStatus.PENDING, nextAttemptAt = next, leaseUntil = null, failure = reason,
                updatedAt = now)
        } else {
            copy(status = ExecutionStatus.FAILED, nextAttemptAt = null, leaseUntil = null, failure = reason,
                updatedAt = now)
        }
    }

    /** Result unknown: keep the attempt open and look again after [retryIn], with the same key. */
    fun unknownResult(now: Instant, retryIn: Duration): Execution =
        copy(leaseUntil = now.plus(retryIn), updatedAt = now)

    /** The day is over and the payment was never tried. */
    fun missed(now: Instant): Execution =
        copy(status = ExecutionStatus.FAILED, nextAttemptAt = null, leaseUntil = null,
            failure = FailureReasons.of(FailureReasons.MISSED_DAY), updatedAt = now)

    fun cancelled(now: Instant): Execution {
        check(status == ExecutionStatus.PENDING) { "execution $id is $status, not PENDING" }
        return copy(status = ExecutionStatus.CANCELLED, nextAttemptAt = null, updatedAt = now)
    }

    companion object {
        fun pending(schedule: Schedule, firstAttemptAt: Instant, now: Instant) = Execution(
            ExecutionId.new(), schedule.id, schedule.tenantId, schedule.executeOn, ExecutionStatus.PENDING, 0,
            firstAttemptAt, null, null, null, null, now,
        )
    }
}

enum class AttemptOutcome { EXECUTED, REFUSED }

/**
 * One call made on the day. [idempotencyKey] is written before the call, so a crash or a timeout
 * repeats the call with the same key and gets the original answer instead of paying twice.
 * [outcome] stays null while the result is unknown - or, for a Pix, while its settlement is awaited
 * ([endToEndId] set, [outcome] still null).
 */
data class Attempt(
    val executionId: ExecutionId,
    val number: Int,
    val idempotencyKey: String,
    val startedAt: Instant,
    val finishedAt: Instant?,
    val outcome: AttemptOutcome?,
    val reason: FailureReason?,
    val transactionId: UUID?,
    val endToEndId: String?,
) {
    /** Accepted by the Pix service but not settled yet: still open. */
    val awaitingResult: Boolean get() = finishedAt == null && endToEndId != null

    fun executed(transactionId: UUID, endToEndId: String?, now: Instant) =
        copy(finishedAt = now, outcome = AttemptOutcome.EXECUTED, transactionId = transactionId,
            endToEndId = endToEndId ?: this.endToEndId)

    fun awaitingResult(endToEndId: String, transactionId: UUID?) =
        copy(endToEndId = endToEndId, transactionId = transactionId ?: this.transactionId)

    fun refused(reason: FailureReason, now: Instant) =
        copy(finishedAt = now, outcome = AttemptOutcome.REFUSED, reason = reason)
}
