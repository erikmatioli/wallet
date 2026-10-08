package br.com.walletapp.api.application.port

import br.com.walletapp.api.domain.AccountId
import br.com.walletapp.api.domain.Cpf
import br.com.walletapp.api.domain.CustomerLogin
import br.com.walletapp.api.domain.LoginId
import br.com.walletapp.api.domain.Password
import br.com.walletapp.contract.Me
import br.com.walletapp.contract.PixPayee
import br.com.walletapp.contract.Schedule
import br.com.walletapp.contract.ScheduleRequest
import br.com.walletapp.contract.StatementPage
import br.com.walletapp.contract.TransferDestination

/**
 * wallet-core, called as the tenant that owns the app (ADR-001, decision 1). The reads answer in the
 * app's contract directly: translating wallet-core for the desktop is what this BFF is for.
 */
interface CoreBanking {

    /** Creates the customer and their account. [externalRef] ties the wallet-core customer to the login. */
    fun onboard(name: String, cpf: Cpf, externalRef: String): Onboarding

    /** The account of the customer with this CPF, if wallet-core has one. */
    fun accountByCpf(cpf: Cpf): AccountId?

    fun me(accountId: AccountId): Me

    fun statement(accountId: AccountId, before: Long?, limit: Int): StatementPage

    /** An account of this institution by its number, with its holder's name; null when there is none. */
    fun findByNumber(branch: String, number: String, checkDigit: String): Pair<AccountId, TransferDestination>?

    /** Idempotent by [idempotencyKey]: the same key returns the original transfer. */
    fun transfer(from: AccountId, to: TransferDestination, amountCents: Long, description: String?,
                 idempotencyKey: String): PaymentOutcome
}

/** What wallet-core or wallet-pix answered to a payment. */
sealed interface PaymentOutcome {
    data class Done(val id: String, val occurredAt: String) : PaymentOutcome

    /** Refused with the service's stable code (INSUFFICIENT_FUNDS...): nothing moved. */
    data class Refused(val code: String) : PaymentOutcome
}

/** wallet-pix's send API, as the tenant that owns the app. */
interface PixGateway {

    /** Idempotent by [idempotencyKey]. [payerCpf] is the login's: the desktop never sends it. */
    fun send(payer: AccountId, payerCpf: Cpf, payee: PixPayee, amountCents: Long, description: String?,
             idempotencyKey: String): PixOutcome

    fun status(endToEndId: String): PixOutcome.Sent?
}

sealed interface PixOutcome {
    /** Taken by wallet-pix: [status] SENT, COMPLETED, REFUNDED or RETURNED. */
    data class Sent(val endToEndId: String, val status: String, val reasonCode: String?) : PixOutcome

    data class Refused(val code: String) : PixOutcome
}

/** The Pix each customer sent: wallet-pix only knows the tenant, this is what ties a Pix to a customer. */
interface SentPixRepository {
    fun record(pix: SentPix)

    fun find(loginId: LoginId, endToEndId: String): SentPix?
}

data class SentPix(val loginId: LoginId, val endToEndId: String, val payeeName: String, val payeeIspb: String,
                   val amountCents: Long)

/**
 * wallet-scheduler's API, as the app's tenant. It filters only by tenant - and every customer is the same
 * tenant - so the payer account comes back with each schedule, for app-api to check it is the customer's.
 */
interface SchedulerGateway {
    fun list(payer: AccountId): List<ScheduleView>

    fun get(id: String): ScheduleView?

    /** Idempotent by [idempotencyKey]. [payerCpf] only matters for a Pix (wallet-pix checks it on the day). */
    fun create(payer: AccountId, payerCpf: Cpf, request: ScheduleRequest, idempotencyKey: String): ScheduleOutcome

    fun cancel(id: String): ScheduleOutcome
}

/** A schedule and whose account pays it. [schedule].canCancel is decided by the service, not here. */
data class ScheduleView(val payer: AccountId, val schedule: Schedule)

sealed interface ScheduleOutcome {
    data class Done(val view: ScheduleView) : ScheduleOutcome

    /** Refused with wallet-scheduler's code (INVALID_EXECUTION_DATE, CANCELLATION_DEADLINE_PASSED...). */
    data class Refused(val code: String) : ScheduleOutcome
}

sealed interface Onboarding {
    data class Created(val accountId: AccountId) : Onboarding

    /** wallet-core already has a customer with this CPF. */
    data object AlreadyExists : Onboarding
}

/** A dependency did not answer; the customer may try again. */
class DependencyUnavailableException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

interface LoginRepository {
    fun find(cpf: Cpf): CustomerLogin?

    fun findById(id: LoginId): CustomerLogin?

    /** @return false when the CPF is already taken (a concurrent signup): nothing was written */
    fun insert(login: CustomerLogin): Boolean

    fun update(login: CustomerLogin)

    fun delete(id: LoginId)
}

interface PasswordHasher {
    fun hash(password: Password): String

    fun matches(raw: String, hash: String): Boolean
}

/** Issues the customer's token (ADR-001, decision 5): signed by app-api, never one of wallet-core's. */
interface SessionTokens {
    fun issue(login: CustomerLogin): IssuedToken
}

data class IssuedToken(val token: String, val expiresInSeconds: Long)
