package br.com.walletscheduler.adapter.`in`.rest

import br.com.walletscheduler.application.port.ScheduleDetails
import br.com.walletscheduler.domain.Attempt
import br.com.walletscheduler.domain.FailureReason
import br.com.walletscheduler.domain.PixDestination
import br.com.walletscheduler.domain.TransferDestination
import jakarta.validation.Valid
import jakarta.validation.constraints.DecimalMin
import jakarta.validation.constraints.Digits
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

// Request and response contracts of the API. Amounts are decimal BRL with 2 places, like wallet-core.
// In Kotlin, a Jakarta annotation on a constructor property needs the "field:" target to reach the field
// the validator reads; without it the annotation lands on the constructor parameter and is ignored.

/**
 * `type` TRANSFER (the default) needs `destination`; PIX needs `pix`. The other block must be absent.
 */
data class CreateScheduleRequest(
    @field:Pattern(regexp = "TRANSFER|PIX") val type: String? = "TRANSFER",
    @field:NotNull val payerAccountId: UUID?,
    @field:NotNull val executeOn: LocalDate?,
    @field:NotNull @field:DecimalMin("0.01") @field:Digits(integer = 15, fraction = 2) val amount: BigDecimal?,
    @field:Size(max = 140) val description: String?,
    @field:Valid val destination: DestinationRequest? = null,
    @field:Valid val pix: PixRequest? = null,
)

data class DestinationRequest(
    @field:NotNull @field:Pattern(regexp = "\\d{4}") val branch: String?,
    @field:NotNull @field:Pattern(regexp = "\\d{1,20}") val number: String?,
    @field:NotNull @field:Pattern(regexp = "\\d") val checkDigit: String?,
)

/** A Pix needs the payer's CPF/CNPJ: the Pix service checks it against the account holder. */
data class PixRequest(
    @field:NotNull @field:Size(max = 32) val payerTaxId: String?,
    @field:NotNull @field:Valid val payee: PixPayeeRequest?,
)

data class PixPayeeRequest(
    @field:NotNull @field:Pattern(regexp = "\\d{8}") val ispb: String?,
    @field:NotNull @field:Pattern(regexp = "\\d{4}") val branch: String?,
    /** Number with the check digit appended, as the SPI carries it. */
    @field:NotNull @field:Pattern(regexp = "\\d{2,21}") val accountNumber: String?,
    @field:NotNull @field:Size(max = 32) val taxId: String?,
    @field:NotNull @field:Size(min = 1, max = 140) val name: String?,
)

data class ScheduleResponse(
    val id: UUID,
    val type: String,
    val status: String,
    val payerAccountId: UUID,
    /** Set for a TRANSFER. */
    val destination: DestinationResponse?,
    /** Set for a PIX. The CPF/CNPJ only masked. */
    val pixPayee: PixPayeeResponse?,
    val amount: BigDecimal,
    val description: String?,
    val executeOn: LocalDate,
    val createdAt: Instant,
    val cancelledAt: Instant?,
    val execution: ExecutionResponse,
    val attempts: List<AttemptResponse>,
) {
    companion object {
        fun from(d: ScheduleDetails): ScheduleResponse {
            val s = d.schedule
            val e = d.execution
            val destination = s.destination
            return ScheduleResponse(
                id = s.id.value,
                type = s.type.name,
                status = s.status.name,
                payerAccountId = s.payerAccountId.value,
                destination = (destination as? TransferDestination)?.let {
                    DestinationResponse(it.branch, it.number, it.checkDigit, it.accountId.value, it.holderName)
                },
                pixPayee = (destination as? PixDestination)?.let {
                    PixPayeeResponse(it.ispb, it.branch, it.accountNumber, it.taxIdMasked, it.holderName)
                },
                amount = BigDecimal.valueOf(s.amountCents, 2),
                description = s.description,
                executeOn = s.executeOn,
                createdAt = s.createdAt,
                cancelledAt = s.cancelledAt,
                execution = ExecutionResponse(e.status.name, e.attemptCount, e.nextAttemptAt, e.transactionId,
                    e.endToEndId, e.failure?.let(ReasonResponse::from)),
                attempts = d.attempts.map(AttemptResponse::from),
            )
        }
    }
}

data class DestinationResponse(val branch: String, val number: String, val checkDigit: String, val accountId: UUID,
                               val holderName: String)

data class PixPayeeResponse(val ispb: String, val branch: String, val accountNumber: String, val taxIdMasked: String,
                            val name: String)

/**
 * [failure]: the last refusal - also while a later window of the day is still to come.
 * [transactionId]: the wallet-core transaction that moved the money (for a Pix, its debit).
 */
data class ExecutionResponse(val status: String, val attemptCount: Int, val nextAttemptAt: Instant?,
                             val transactionId: UUID?, val endToEndId: String?, val failure: ReasonResponse?)

data class ReasonResponse(val code: String, val message: String, val retryToday: Boolean) {
    companion object {
        fun from(r: FailureReason) = ReasonResponse(r.code, r.message, r.retryToday)
    }
}

/** [outcome] null while the result is unknown or, for a Pix, while its settlement is awaited. */
data class AttemptResponse(val number: Int, val startedAt: Instant, val finishedAt: Instant?, val outcome: String?,
                           val reason: ReasonResponse?, val transactionId: UUID?, val endToEndId: String?) {
    companion object {
        fun from(a: Attempt) = AttemptResponse(a.number, a.startedAt, a.finishedAt, a.outcome?.name,
            a.reason?.let(ReasonResponse::from), a.transactionId, a.endToEndId)
    }
}
