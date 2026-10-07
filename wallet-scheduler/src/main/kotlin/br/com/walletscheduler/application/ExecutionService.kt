package br.com.walletscheduler.application

import br.com.walletscheduler.application.port.NotReadyYetException
import br.com.walletscheduler.application.port.PaymentResult
import br.com.walletscheduler.application.port.PixOutcome
import br.com.walletscheduler.application.port.PixPayments
import br.com.walletscheduler.application.port.ScheduleRepository
import br.com.walletscheduler.application.port.Transactions
import br.com.walletscheduler.application.port.WalletCore
import br.com.walletscheduler.domain.Attempt
import br.com.walletscheduler.domain.Execution
import br.com.walletscheduler.domain.ExecutionStatus
import br.com.walletscheduler.domain.ExecutionWindows
import br.com.walletscheduler.domain.FailureReasons
import br.com.walletscheduler.domain.PixDestination
import br.com.walletscheduler.domain.Schedule
import br.com.walletscheduler.domain.TenantId
import br.com.walletscheduler.domain.TransferDestination
import java.time.Clock
import java.time.Duration

/**
 * Runs schedules on their day (ADR-001, decisions 5 and 6), in two steps the job calls in turn:
 *
 * 1. [claim]: in one short database transaction, lock the due executions (SKIP LOCKED), open an
 *    attempt for each - its idempotency key written before any call - and lease it.
 * 2. [execute]: outside any transaction, call wallet-core (transfer) or wallet-pix (Pix), then record
 *    the answer.
 *
 * A crash between the two leaves the execution PROCESSING; when the lease expires, [claim] takes
 * the same attempt back and [execute] repeats the call with the same key, so the payment is answered
 * with the original instead of being made twice.
 *
 * A Pix accepted by wallet-pix stays PROCESSING until its result event ([onPixEvent]); if the event is
 * lost, the lease expires and the same attempt asks wallet-pix again, which answers with the Pix as it is.
 */
class ExecutionService(
    private val walletCore: WalletCore,
    private val pix: PixPayments,
    private val schedules: ScheduleRepository,
    private val tx: Transactions,
    private val windows: ExecutionWindows,
    private val clock: Clock,
    private val settings: Settings = Settings(),
) {

    /**
     * @property lease how long a claimed attempt belongs to one worker; past it, another may take it back
     * @property unknownRetryIn how soon an attempt with an unknown result is tried again (same key)
     * @property pixResultWait how long a sent Pix waits for its result event before asking wallet-pix again
     */
    data class Settings(
        val batchSize: Int = 50,
        val lease: Duration = Duration.ofMinutes(2),
        val unknownRetryIn: Duration = Duration.ofMinutes(1),
        val pixResultWait: Duration = Duration.ofMinutes(10),
    )

    /** An attempt ready to be made: the call is all that is left. */
    data class Work(val schedule: Schedule, val execution: Execution, val attempt: Attempt) {
        val tenantId: TenantId get() = schedule.tenantId
    }

    enum class Outcome { EXECUTED, AWAITING_RESULT, RETRY_LATER, FAILED, UNKNOWN, ALREADY_RECORDED }

    fun claim(): List<Work> = tx.inTransaction {
        val now = clock.instant()
        schedules.lockDueExecutions(now, settings.batchSize).mapNotNull { execution ->
            val schedule = schedules.findSchedule(execution.scheduleId)
            when (execution.status) {
                ExecutionStatus.PENDING -> {
                    if (windows.today(now).isAfter(execution.executeOn)) {
                        // The service was down past the last window: never pay on another day.
                        schedules.update(execution.missed(now))
                        schedules.update(schedule.completed())
                        null
                    } else {
                        val (started, attempt) = execution.startAttempt(now, settings.lease)
                        schedules.update(started)
                        schedules.insert(attempt)
                        Work(schedule, started, attempt)
                    }
                }
                // Lease expired: whoever had it crashed, got no answer, or no Pix result arrived. Same attempt, same key.
                ExecutionStatus.PROCESSING -> {
                    val attempt = schedules.findOpenAttempt(execution.id)
                        ?: error("execution ${execution.id} is PROCESSING without an open attempt")
                    val resumed = execution.resume(now, settings.lease)
                    schedules.update(resumed)
                    Work(schedule, resumed, attempt)
                }
                else -> null
            }
        }
    }

    fun execute(work: Work): Outcome {
        val s = work.schedule
        val key = work.attempt.idempotencyKey
        val result = when (val d = s.destination) {
            is TransferDestination -> walletCore.transfer(s.clientId, s.payerAccountId, d.accountId, s.amountCents,
                s.description ?: "Transferência agendada", key)
            is PixDestination -> pix.send(s.clientId, s.payerAccountId, s.payerTaxId!!, d, s.amountCents,
                s.description ?: "Pix agendado", key)
        }
        return tx.inTransaction { record(work.tenantId, work.schedule, work.attempt.number, result) }
    }

    /**
     * The result of a Pix this service sent: wallet-pix publishes it, with the attempt's key as requestId.
     * Events about Pix this service did not send, or already recorded (a redelivery), are ignored.
     *
     * @return the schedule's tenant when the event was ours, for the caller's logs
     * @throws NotReadyYetException when the attempt has not recorded wallet-pix's answer yet
     */
    fun onPixEvent(event: PixOutcome): Pair<TenantId, Outcome>? = tx.inTransaction {
        val attempt = schedules.findAttemptByKey(event.requestId) ?: return@inTransaction null
        val execution = schedules.findExecution(attempt.executionId)
        val schedule = schedules.findSchedule(execution.scheduleId)
        val result = if (event.settled) {
            // The debit's id was recorded with the Pix service's answer; if that answer is not recorded
            // yet, the event is early - record() finds out, under the lock, and asks for a redelivery.
            PaymentResult.Executed(transactionId = null, endToEndId = attempt.endToEndId)
        } else {
            PaymentResult.Refused(event.reasonCode ?: "PIX_REFUNDED", null)
        }
        schedule.tenantId to record(schedule.tenantId, schedule, attempt.number, result, fromEvent = true)
    }

    private fun record(tenantId: TenantId, scheduleSnapshot: Schedule, attemptNumber: Int, result: PaymentResult,
                       fromEvent: Boolean = false): Outcome {
        val now = clock.instant()
        // Lock first, then look: two workers (or a worker and an event) recording the same attempt are serialized here.
        val (schedule, execution) = schedules.lock(tenantId, scheduleSnapshot.id) ?: error("schedule vanished")
        // Another worker may have taken this attempt back after our lease expired and already recorded it;
        // the payment service gave both of us the same answer (same key), so there is nothing left to do.
        val attempt = schedules.findOpenAttempt(execution.id)
        if (attempt == null || attempt.number != attemptNumber) {
            return Outcome.ALREADY_RECORDED
        }
        if (fromEvent && !attempt.awaitingResult) {
            throw NotReadyYetException("Pix result for ${attempt.idempotencyKey} arrived before the send was recorded")
        }
        return when (result) {
            is PaymentResult.Executed -> {
                val transactionId = result.transactionId ?: attempt.transactionId
                    ?: error("attempt ${attempt.idempotencyKey} settled without the debit's transaction id")
                schedules.update(attempt.executed(transactionId, result.endToEndId, now))
                schedules.update(execution.executed(transactionId, result.endToEndId, now))
                schedules.update(schedule.completed())
                Outcome.EXECUTED
            }
            is PaymentResult.Pending -> {
                schedules.update(attempt.awaitingResult(result.endToEndId, result.transactionId))
                schedules.update(execution.awaitingResult(result.endToEndId, result.transactionId, now,
                    settings.pixResultWait))
                Outcome.AWAITING_RESULT
            }
            is PaymentResult.Refused -> {
                val reason = FailureReasons.of(result.code)
                val next = execution.refused(reason, now, windows)
                schedules.update(attempt.refused(reason, now))
                schedules.update(next)
                if (next.status == ExecutionStatus.FAILED) {
                    schedules.update(schedule.completed())
                    Outcome.FAILED
                } else {
                    Outcome.RETRY_LATER
                }
            }
            is PaymentResult.Unknown -> {
                schedules.update(execution.unknownResult(now, settings.unknownRetryIn))
                Outcome.UNKNOWN
            }
        }
    }
}
