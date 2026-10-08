package br.com.walletapp.contract

import kotlinx.serialization.Serializable

// Payments (ADR-001, step 3). Every POST here needs the `Idempotency-Key` header: the desktop creates it on
// the confirmation screen and repeats it on a retry, so a double click or a timeout never pays twice.

/** `GET /app/v1/transfers/destination?branch=&number=&checkDigit=`: who receives, for the confirmation screen. */
@Serializable
data class TransferDestination(val holderName: String, val branch: String, val number: String, val checkDigit: String)

/** `POST /app/v1/transfers`: to another account of this institution. The payer account comes from the token. */
@Serializable
data class TransferRequest(
    val branch: String,
    val number: String,
    val checkDigit: String,
    val amountCents: Long,
    val description: String? = null,
)

@Serializable
data class TransferReceipt(
    val transactionId: String,
    val amountCents: Long,
    val occurredAt: String,
    val destination: TransferDestination,
    val description: String? = null,
)

/** The payee of a Pix: another institution's account. [accountNumber] includes the check digit. */
@Serializable
data class PixPayee(val ispb: String, val branch: String, val accountNumber: String, val taxId: String, val name: String)

/** `POST /app/v1/pix`. The payer's CPF comes from the login, never from the desktop. */
@Serializable
data class PixRequest(val payee: PixPayee, val amountCents: Long, val description: String? = null)

/**
 * `POST /app/v1/pix` and `GET /app/v1/pix/{endToEndId}`. [status]: SENT (on its way), COMPLETED
 * (settled), REFUNDED (rejected; the money came back, [reasonCode] says why) or RETURNED.
 */
@Serializable
data class PixReceipt(
    val endToEndId: String,
    val status: String,
    val amountCents: Long,
    val payeeName: String,
    val payeeIspb: String,
    val reasonCode: String? = null,
    val reasonMessage: String? = null,
)
