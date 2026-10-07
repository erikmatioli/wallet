package br.com.walletscheduler.adapter.out.walletcore

import br.com.walletscheduler.application.port.CoreAccount
import br.com.walletscheduler.application.port.DependencyUnavailableException
import br.com.walletscheduler.application.port.PaymentResult
import br.com.walletscheduler.application.port.WalletCore
import br.com.walletscheduler.config.SchedulerProperties
import br.com.walletscheduler.domain.AccountId
import org.slf4j.LoggerFactory
import org.springframework.http.HttpHeaders
import org.springframework.http.client.SimpleClientHttpRequestFactory
import org.springframework.stereotype.Component
import org.springframework.web.client.HttpClientErrorException
import org.springframework.web.client.HttpServerErrorException
import org.springframework.web.client.ResourceAccessException
import org.springframework.web.client.RestClient
import org.springframework.web.client.body
import java.math.BigDecimal
import java.util.UUID

/**
 * wallet-core over HTTP, as the tenant that owns the schedule - so wallet-core's Row Level Security
 * still scopes every call to that tenant's accounts. Same error mapping as wallet-pix's client: network
 * failures, 5xx and 429 are "unknown" (or "unavailable" on the API path); a 4xx with a code is
 * wallet-core's answer.
 */
@Component
class WalletCoreClient(builder: RestClient.Builder, props: SchedulerProperties, private val tokens: WalletCoreTokens) :
    WalletCore {

    private val log = LoggerFactory.getLogger(javaClass)

    private val http: RestClient = builder
        .baseUrl(props.walletCore.baseUrl.toString())
        .requestFactory(SimpleClientHttpRequestFactory().apply {
            setConnectTimeout(props.walletCore.connectTimeout)
            setReadTimeout(props.walletCore.readTimeout)
        })
        .build()

    private data class AccountDetail(val id: UUID, val branch: String, val number: String, val checkDigit: String,
                                     val status: String, val customerName: String?)
    private data class AccountSummary(val id: UUID)
    private data class HolderCheckRequest(val branch: String, val number: String, val checkDigit: String,
                                          val taxId: String)
    private data class HolderCheckResponse(val result: String)
    private data class TransferRequest(val sourceAccountId: UUID, val destinationAccountId: UUID,
                                       val amount: BigDecimal, val description: String?)
    private data class TransactionResponse(val id: UUID, val replayed: Boolean)

    override fun serves(clientId: String) = tokens.knows(clientId)

    override fun findAccount(clientId: String, accountId: AccountId): CoreAccount? = read(clientId, "account lookup") { token ->
        http.get().uri("/v1/accounts/{id}", accountId.value)
            .header(HttpHeaders.AUTHORIZATION, "Bearer $token")
            .retrieve().body<AccountDetail>()
    }?.let { CoreAccount(AccountId(it.id), it.branch, it.number, it.checkDigit, it.status, it.customerName.orEmpty()) }

    /** wallet-core's lookup has no holder name; the account detail has, so it is a second call. */
    override fun findAccountByNumber(clientId: String, branch: String, number: String, checkDigit: String): CoreAccount? {
        val found = read(clientId, "account lookup by number") { token ->
            http.get().uri("/v1/accounts/lookup?branch={b}&number={n}&checkDigit={d}", branch, number, checkDigit)
                .header(HttpHeaders.AUTHORIZATION, "Bearer $token")
                .retrieve().body<AccountSummary>()
        } ?: return null
        return findAccount(clientId, AccountId(found.id))
    }

    override fun checkHolder(clientId: String, account: CoreAccount, taxId: String): String =
        read(clientId, "holder check") { token ->
            http.post().uri("/v1/accounts/holder-check")
                .header(HttpHeaders.AUTHORIZATION, "Bearer $token")
                .body(HolderCheckRequest(account.branch, account.number, account.checkDigit, taxId))
                .retrieve().body<HolderCheckResponse>()
        }?.result ?: "ACCOUNT_NOT_FOUND"

    override fun transfer(clientId: String, from: AccountId, to: AccountId, amountCents: Long, description: String?,
                          idempotencyKey: String): PaymentResult = try {
        val response = tokens.withToken(clientId) { token ->
            http.post().uri("/v1/transfers")
                .header(HttpHeaders.AUTHORIZATION, "Bearer $token")
                .header("Idempotency-Key", idempotencyKey)
                .body(TransferRequest(from.value, to.value, BigDecimal.valueOf(amountCents, 2), description))
                .retrieve().body<TransactionResponse>()!!
        }
        if (response.replayed) log.info("transfer {} was already made (idempotent replay): {}", idempotencyKey, response.id)
        PaymentResult.Executed(response.id)
    } catch (e: Exception) {
        PaymentErrors.toResult(e, "wallet-core transfer")
    }

    /** API-path reads: 404 means "no such account"; anything technical is "try again later". */
    private fun <T : Any> read(clientId: String, operation: String, request: (String) -> T?): T? = try {
        tokens.withToken(clientId, null, request)
    } catch (e: HttpClientErrorException.NotFound) {
        null
    } catch (e: HttpServerErrorException) {
        throw DependencyUnavailableException("wallet-core $operation failed (${e.statusCode.value()})", e)
    } catch (e: ResourceAccessException) {
        throw DependencyUnavailableException("wallet-core $operation failed: ${e.message}", e)
    }
}
