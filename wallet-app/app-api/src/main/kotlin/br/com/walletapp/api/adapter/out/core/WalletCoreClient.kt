package br.com.walletapp.api.adapter.out.core

import br.com.walletapp.api.application.port.CoreBanking
import br.com.walletapp.api.application.port.DependencyUnavailableException
import br.com.walletapp.api.application.port.Onboarding
import br.com.walletapp.api.application.port.PaymentOutcome
import br.com.walletapp.api.config.AppProperties
import br.com.walletapp.api.domain.AccountId
import br.com.walletapp.api.domain.Cpf
import br.com.walletapp.contract.Me
import br.com.walletapp.contract.PixInfo
import br.com.walletapp.contract.StatementEntry
import br.com.walletapp.contract.StatementPage
import br.com.walletapp.contract.TransferDestination
import org.springframework.http.HttpHeaders
import org.springframework.http.client.SimpleClientHttpRequestFactory
import org.springframework.stereotype.Component
import org.springframework.web.client.HttpClientErrorException
import org.springframework.web.client.HttpServerErrorException
import org.springframework.web.client.ResourceAccessException
import org.springframework.web.client.RestClient
import org.springframework.web.client.body
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

/**
 * wallet-core, as the tenant that owns the app. Its token never leaves app-api. wallet-core's JSON is
 * read with Jackson into the private classes below and translated into the app's contract here.
 */
@Component
class WalletCoreClient(builder: RestClient.Builder, props: AppProperties, private val tokens: CoreTokens) : CoreBanking {

    private val core = props.walletCore
    private val http: RestClient = builder
        .baseUrl(core.baseUrl.toString())
        .requestFactory(SimpleClientHttpRequestFactory().apply {
            setConnectTimeout(core.connectTimeout)
            setReadTimeout(core.readTimeout)
        })
        .build()
    private data class OnboardRequest(val name: String, val taxId: String, val externalRef: String)
    private data class OnboardResponse(val account: AccountRef)
    private data class AccountRef(val id: UUID)
    private data class AccountDetail(val branch: String, val number: String, val checkDigit: String, val status: String,
                                     val balance: BigDecimal, val customerName: String?)
    private data class CoreStatement(val entries: List<CoreEntry>, val nextBefore: Long?)
    private data class CoreEntry(val transactionId: UUID, val sequence: Long, val type: String, val direction: String,
                                 val amount: BigDecimal, val balanceAfter: BigDecimal, val description: String?,
                                 val occurredAt: Instant, val counterpartyCustomerName: String?, val pix: CorePix?)
    private data class CorePix(val endToEndId: String, val counterparty: CorePixCounterparty?, val reasonCode: String?)
    private data class CorePixCounterparty(val name: String?, val taxIdMasked: String?, val ispb: String?)
    private data class CoreTransfer(val sourceAccountId: UUID, val destination: CoreNumber, val amount: BigDecimal,
                                    val description: String?)
    private data class CoreNumber(val branch: String, val number: String, val checkDigit: String)
    private data class CoreTransaction(val id: UUID, val occurredAt: Instant)
    private data class Problem(val code: String?)

    override fun onboard(name: String, cpf: Cpf, externalRef: String): Onboarding = call("onboarding") { token ->
        try {
            val r = http.post().uri("/v1/customers")
                .header(HttpHeaders.AUTHORIZATION, "Bearer $token")
                .body(OnboardRequest(name, cpf.digits, externalRef))
                .retrieve().body<OnboardResponse>()!!
            Onboarding.Created(AccountId(r.account.id))
        } catch (e: HttpClientErrorException.Conflict) {
            Onboarding.AlreadyExists
        }
    }

    override fun accountByCpf(cpf: Cpf): AccountId? = call("account by CPF") { token ->
        try {
            http.get().uri("/v1/accounts/findByTaxId?taxId={t}", cpf.digits)
                .header(HttpHeaders.AUTHORIZATION, "Bearer $token")
                .retrieve().body<AccountRef>()?.let { AccountId(it.id) }
        } catch (e: HttpClientErrorException.NotFound) {
            null
        }
    }

    override fun me(accountId: AccountId): Me = call("account") { token ->
        val a = http.get().uri("/v1/accounts/{id}", accountId.value)
            .header(HttpHeaders.AUTHORIZATION, "Bearer $token")
            .retrieve().body<AccountDetail>()!!
        Me(a.customerName.orEmpty(), a.branch, a.number, a.checkDigit, a.status, cents(a.balance))
    }

    override fun statement(accountId: AccountId, before: Long?, limit: Int): StatementPage = call("statement") { token ->
        val uri = if (before == null) "/v1/accounts/{id}/statement?limit={l}" else "/v1/accounts/{id}/statement?limit={l}&before={b}"
        val args: Array<Any> = listOfNotNull<Any>(accountId.value, limit, before).toTypedArray()
        val s = http.get().uri(uri, *args)
            .header(HttpHeaders.AUTHORIZATION, "Bearer $token")
            .retrieve().body<CoreStatement>()!!
        StatementPage(s.entries.map(::entry), s.nextBefore)
    }

    override fun findByNumber(branch: String, number: String, checkDigit: String): Pair<AccountId, TransferDestination>? =
        call("account by number") { token ->
            val found = try {
                http.get().uri("/v1/accounts/lookup?branch={b}&number={n}&checkDigit={d}", branch, number, checkDigit)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer $token")
                    .retrieve().body<AccountRef>()!!
            } catch (e: HttpClientErrorException.NotFound) {
                return@call null
            }
            val detail = http.get().uri("/v1/accounts/{id}", found.id)
                .header(HttpHeaders.AUTHORIZATION, "Bearer $token")
                .retrieve().body<AccountDetail>()!!
            AccountId(found.id) to TransferDestination(detail.customerName.orEmpty(), detail.branch, detail.number,
                detail.checkDigit)
        }

    override fun transfer(from: AccountId, to: TransferDestination, amountCents: Long, description: String?,
                          idempotencyKey: String): PaymentOutcome = call("transfer") { token ->
        try {
            val t = http.post().uri("/v1/transfers")
                .header(HttpHeaders.AUTHORIZATION, "Bearer $token")
                .header("Idempotency-Key", idempotencyKey)
                .body(CoreTransfer(from.value, CoreNumber(to.branch, to.number, to.checkDigit),
                    BigDecimal.valueOf(amountCents, 2), description))
                .retrieve().body<CoreTransaction>()!!
            PaymentOutcome.Done(t.id.toString(), t.occurredAt.toString())
        } catch (e: HttpClientErrorException) {
            // 404, 409, 422: wallet-core answered, nothing moved. Its code says why.
            if (e is HttpClientErrorException.Unauthorized) throw e
            val code = runCatching { e.getResponseBodyAs(Problem::class.java)?.code }.getOrNull()
            PaymentOutcome.Refused(code ?: "HTTP_${e.statusCode.value()}")
        }
    }

    private fun entry(e: CoreEntry) = StatementEntry(
        transactionId = e.transactionId.toString(),
        sequence = e.sequence,
        type = e.type,
        credit = e.direction == "CREDIT",
        amountCents = cents(e.amount),
        balanceAfterCents = cents(e.balanceAfter),
        description = e.description?.takeIf { it.isNotBlank() },
        occurredAt = e.occurredAt.toString(),
        counterpartyName = e.counterpartyCustomerName ?: e.pix?.counterparty?.name,
        pix = e.pix?.let { PixInfo(it.endToEndId, it.counterparty?.taxIdMasked, it.counterparty?.ispb, it.reasonCode) },
    )

    /** Every call as the tenant; network failures and 5xx become "try again", which the API answers with 503. */
    private fun <T> call(operation: String, request: (String) -> T): T = try {
        tokens.withToken(null, request)
    } catch (e: HttpServerErrorException) {
        throw DependencyUnavailableException("wallet-core $operation failed (${e.statusCode.value()})", e)
    } catch (e: ResourceAccessException) {
        throw DependencyUnavailableException("wallet-core $operation failed: ${e.message}", e)
    }

    private companion object {
        fun cents(amount: BigDecimal): Long = amount.movePointRight(2).longValueExact()
    }
}
