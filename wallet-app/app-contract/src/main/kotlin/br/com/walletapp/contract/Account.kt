package br.com.walletapp.contract

import kotlinx.serialization.Serializable

// Money is always in cents (Long) in the contract, never a floating point number (ADR-001, decision 6).
// Instants are ISO-8601 strings (e.g. "2026-10-07T15:00:00Z").

/**
 * `GET /app/v1/me`: the logged-in customer's account.
 *
 * @property canPay whether the account may pay now: false under any block of wallet-core's ADR-013 (the app hides
 *   the payment actions and says why)
 * @property canReceive whether it may receive: false only under a total (administrative) block
 * @property blockedForFees whether the only block is the fee block (wallet-core's ADR-014): a fee was not paid for
 *   lack of balance, and the next credit pays it and frees the account. The app says that instead of sending the
 *   customer to the service desk
 */
@Serializable
data class Me(
    val customerName: String,
    val branch: String,
    val accountNumber: String,
    val checkDigit: String,
    val status: String,
    val balanceCents: Long,
    val canPay: Boolean = true,
    val canReceive: Boolean = true,
    val blockedForFees: Boolean = false,
)

/** `GET /app/v1/statement?before=&limit=`: newest first; [nextBefore] is the cursor of the next page, or null. */
@Serializable
data class StatementPage(val entries: List<StatementEntry>, val nextBefore: Long? = null)

/**
 * One line of the statement, with everything its detail shows - the screen never asks again.
 *
 * @property type wallet-core's type: DEPOSIT, WITHDRAWAL, TRANSFER, PIX_IN, PIX_OUT, PIX_REFUND, PIX_RETURN_IN...
 * @property counterpartyName the other customer of a transfer, or the Pix counterparty; null for deposits
 * @property counterpartyAccount the other customer's account in a transfer, so the app can send to it again;
 *   for a Pix the account is in [pix]
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
    val counterpartyAccount: CounterpartyAccount? = null,
    val pix: PixInfo? = null,
)

/**
 * `GET /app/v1/balance-history?from=&to=`: the balance at the end of each day (midnight in Brasília), oldest
 * first, days without entries included (wallet-core's ADR-012). Only days that are over; [from] and [to] are
 * the period app-api answered (by default, the last 30 closed days).
 */
@Serializable
data class BalanceHistory(val from: String, val to: String, val days: List<DayBalance>)

/** [date] is yyyy-MM-dd. */
@Serializable
data class DayBalance(val date: String, val balanceCents: Long)

/** An account of this institution, as a transfer needs it. */
@Serializable
data class CounterpartyAccount(val branch: String, val number: String, val checkDigit: String)

/**
 * The Pix part of a statement entry. The counterparty's CPF/CNPJ only masked: wallet-core keeps no more
 * (its ADR-010), so a Pix back to the same person needs the document typed again.
 *
 * @property counterpartyInstitution the counterparty's institution (ISPB)
 * @property counterpartyAccount the counterparty's account number with its check digit, as the SPI carries it
 */
@Serializable
data class PixInfo(
    val endToEndId: String,
    val counterpartyTaxIdMasked: String? = null,
    val counterpartyInstitution: String? = null,
    val reasonCode: String? = null,
    val counterpartyBranch: String? = null,
    val counterpartyAccount: String? = null,
)
