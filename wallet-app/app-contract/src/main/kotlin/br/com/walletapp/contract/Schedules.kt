package br.com.walletapp.contract

import kotlinx.serialization.Serializable

// Schedules (ADR-001, step 4): a transfer or a Pix for a future day, run by wallet-scheduler.

/** Where a scheduled transfer goes: an account of this institution. */
@Serializable
data class TransferTarget(val branch: String, val number: String, val checkDigit: String)

/**
 * `POST /app/v1/schedules` (with `Idempotency-Key`). [type] TRANSFER needs [transfer]; PIX needs [pix].
 * [executeOn] is a date (yyyy-MM-dd), from tomorrow on. The payer account and, for a Pix, the payer's CPF
 * come from the customer's session.
 */
@Serializable
data class ScheduleRequest(
    val type: String,
    val executeOn: String,
    val amountCents: Long,
    val description: String? = null,
    val transfer: TransferTarget? = null,
    val pix: PixPayee? = null,
)

/**
 * A schedule as the app shows it: list line and detail at once (the detail needs no new request).
 *
 * @property payee who receives, for the list
 * @property payeeDetail where it goes: "0001 / 00100161-0" for a transfer, "ISPB 99999999 · 0042 / 1234565" for a Pix
 * @property canCancel decided by app-api (until the day before, in Brasília), not by the customer's clock
 */
@Serializable
data class Schedule(
    val id: String,
    val type: String,
    val status: String,
    val executeOn: String,
    val amountCents: Long,
    val description: String? = null,
    val payee: String,
    val payeeDetail: String,
    val createdAt: String,
    val canCancel: Boolean,
    val execution: ScheduleExecution,
    val attempts: List<ScheduleAttempt> = emptyList(),
)

/**
 * [status]: PENDING, PROCESSING, EXECUTED, FAILED or CANCELLED. [failureMessage]: the last refusal, also
 * while a later window of the day is still to come ([nextAttemptAt]).
 */
@Serializable
data class ScheduleExecution(
    val status: String,
    val attemptCount: Int,
    val nextAttemptAt: String? = null,
    val failureMessage: String? = null,
    val transactionId: String? = null,
    val endToEndId: String? = null,
)

/** One try on the day: [outcome] EXECUTED, REFUSED, or null while its result is not known yet. */
@Serializable
data class ScheduleAttempt(val startedAt: String, val outcome: String? = null, val reasonMessage: String? = null)
