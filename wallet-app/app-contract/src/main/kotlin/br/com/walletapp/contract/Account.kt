package br.com.walletapp.contract

import kotlinx.serialization.Serializable

// Money is always in cents (Long) in the contract, never a floating point number (ADR-001, decision 6).
// Instants are ISO-8601 strings (e.g. "2026-10-07T15:00:00Z").

/** `GET /app/v1/me`: the logged-in customer's account. */
@Serializable
data class Me(
    val customerName: String,
    val branch: String,
    val accountNumber: String,
    val checkDigit: String,
    val status: String,
    val balanceCents: Long,
)

/** `GET /app/v1/statement?before=&limit=`: newest first; [nextBefore] is the cursor of the next page, or null. */
@Serializable
data class StatementPage(val entries: List<StatementEntry>, val nextBefore: Long? = null)

/**
 * One line of the statement, with everything its detail shows - the screen never asks again.
 *
 * @property type wallet-core's type: DEPOSIT, WITHDRAWAL, TRANSFER, PIX_IN, PIX_OUT, PIX_REFUND, PIX_RETURN_IN...
 * @property counterpartyName the other customer of a transfer, or the Pix counterparty; null for deposits
 */
@Serializable
data class StatementEntry(
    val transactionId: String,
    val sequence: Long,
    val type: String,
    val credit: Boolean,
    val amountCents: Long,
    val balanceAfterCents: Long,
    val description: String? = null,
    val occurredAt: String,
    val counterpartyName: String? = null,
    val pix: PixInfo? = null,
)

/** The Pix part of a statement entry. The counterparty's CPF/CNPJ only masked. */
@Serializable
data class PixInfo(
    val endToEndId: String,
    val counterpartyTaxIdMasked: String? = null,
    val counterpartyInstitution: String? = null,
    val reasonCode: String? = null,
)
