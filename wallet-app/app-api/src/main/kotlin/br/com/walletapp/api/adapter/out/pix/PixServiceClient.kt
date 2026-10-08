package br.com.walletapp.api.adapter.out.pix

import br.com.walletapp.api.adapter.out.core.CoreTokens
import br.com.walletapp.api.application.port.DependencyUnavailableException
import br.com.walletapp.api.application.port.PixGateway
import br.com.walletapp.api.application.port.PixOutcome
import br.com.walletapp.api.config.AppProperties
import br.com.walletapp.api.domain.AccountId
import br.com.walletapp.api.domain.Cpf
import br.com.walletapp.contract.PixPayee
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

/** wallet-pix's send API, as the app's tenant, with a wallet-core token down-scoped to pix:send. */
@Component
class PixServiceClient(builder: RestClient.Builder, props: AppProperties, private val tokens: CoreTokens) : PixGateway {

    private val http: RestClient = builder
        .baseUrl(props.pix.baseUrl.toString())
        .requestFactory(SimpleClientHttpRequestFactory().apply {
            setConnectTimeout(props.pix.connectTimeout)
            setReadTimeout(props.pix.readTimeout)
        })
        .build()

    private data class Payee(val ispb: String, val branch: String, val accountNumber: String, val taxId: String, val name: String)
    private data class SendRequest(val payerAccountId: UUID, val payerTaxId: String, val payee: Payee,
                                   val amount: BigDecimal, val description: String?)
    private data class Payment(val endToEndId: String, val status: String, val reasonCode: String?)
    private data class Problem(val code: String?)

    override fun send(payer: AccountId, payerCpf: Cpf, payee: PixPayee, amountCents: Long, description: String?,
                      idempotencyKey: String): PixOutcome = call("send") { token ->
        try {
            val p = http.post().uri("/v1/pix/payments")
                .header(HttpHeaders.AUTHORIZATION, "Bearer $token")
                .header("Idempotency-Key", idempotencyKey)
                .body(SendRequest(payer.value, payerCpf.digits,
                    Payee(payee.ispb, payee.branch, payee.accountNumber, payee.taxId.filter(Char::isLetterOrDigit), payee.name),
                    BigDecimal.valueOf(amountCents, 2), description))
                .retrieve().body<Payment>()!!
            PixOutcome.Sent(p.endToEndId, p.status, p.reasonCode)
        } catch (e: HttpClientErrorException) {
            if (e is HttpClientErrorException.Unauthorized) throw e
            // 400, 403, 409, 422: wallet-pix answered and debited nothing. Its code says why.
            val code = runCatching { e.getResponseBodyAs(Problem::class.java)?.code }.getOrNull()
            PixOutcome.Refused(code ?: "HTTP_${e.statusCode.value()}")
        }
    }

    override fun status(endToEndId: String): PixOutcome.Sent? = call("status") { token ->
        try {
            val p = http.get().uri("/v1/pix/payments/{id}", endToEndId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer $token")
                .retrieve().body<Payment>()!!
            PixOutcome.Sent(p.endToEndId, p.status, p.reasonCode)
        } catch (e: HttpClientErrorException.NotFound) {
            null
        }
    }

    /**
     * Network failures and 5xx become "try again" (503 to the desktop). A send repeated with the same
     * Idempotency-Key is answered with the original payment, so trying again never sends twice.
     */
    private fun <T> call(operation: String, request: (String) -> T): T = try {
        tokens.withToken(PIX_SEND, request)
    } catch (e: HttpServerErrorException) {
        throw DependencyUnavailableException("wallet-pix $operation failed (${e.statusCode.value()})", e)
    } catch (e: ResourceAccessException) {
        throw DependencyUnavailableException("wallet-pix $operation failed: ${e.message}", e)
    }

    private companion object {
        const val PIX_SEND = "pix:send"
    }
}
