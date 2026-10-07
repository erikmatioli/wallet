package br.com.walletscheduler.adapter.out.pix

import br.com.walletscheduler.adapter.out.walletcore.PaymentErrors
import br.com.walletscheduler.adapter.out.walletcore.WalletCoreTokens
import br.com.walletscheduler.application.port.PaymentResult
import br.com.walletscheduler.application.port.PixPayments
import br.com.walletscheduler.config.SchedulerProperties
import br.com.walletscheduler.domain.AccountId
import br.com.walletscheduler.domain.PixDestination
import org.springframework.http.HttpHeaders
import org.springframework.http.client.SimpleClientHttpRequestFactory
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import org.springframework.web.client.body
import java.math.BigDecimal
import java.util.UUID

/**
 * wallet-pix's send API (`POST /v1/pix/payments`), with a wallet-core token down-scoped to pix:send for
 * the tenant that owns the schedule. A repeated Idempotency-Key does not send again: wallet-pix answers
 * with the payment as it is now, so the same call also tells whether a Pix already settled.
 */
@Component
class PixServiceClient(builder: RestClient.Builder, props: SchedulerProperties, private val tokens: WalletCoreTokens) :
    PixPayments {

    private val http: RestClient = builder
        .baseUrl(props.pix.baseUrl.toString())
        .requestFactory(SimpleClientHttpRequestFactory().apply {
            setConnectTimeout(props.pix.connectTimeout)
            setReadTimeout(props.pix.readTimeout)
        })
        .build()

    private data class Payee(val ispb: String, val branch: String, val accountNumber: String, val taxId: String,
                             val name: String)
    private data class PaymentRequest(val payerAccountId: UUID, val payerTaxId: String, val payee: Payee,
                                      val amount: BigDecimal, val description: String?)
    private data class PaymentResponse(val endToEndId: String, val status: String, val debitTransactionId: UUID?,
                                       val reasonCode: String?)

    override fun send(clientId: String, payerAccountId: AccountId, payerTaxId: String, payee: PixDestination,
                      amountCents: Long, description: String?, idempotencyKey: String): PaymentResult = try {
        val p = tokens.withToken(clientId, PIX_SEND) { token ->
            http.post().uri("/v1/pix/payments")
                .header(HttpHeaders.AUTHORIZATION, "Bearer $token")
                .header("Idempotency-Key", idempotencyKey)
                .body(PaymentRequest(payerAccountId.value, payerTaxId,
                    Payee(payee.ispb, payee.branch, payee.accountNumber, payee.taxId, payee.holderName),
                    BigDecimal.valueOf(amountCents, 2), description))
                .retrieve().body<PaymentResponse>()!!
        }
        when (p.status) {
            // SENT: the payer was debited and the pacs.008 left; the result comes as an event.
            "SENT" -> PaymentResult.Pending(p.endToEndId, p.debitTransactionId)
            // A repeated key whose Pix already settled (its event was lost or is still on the way).
            "COMPLETED", "RETURNED" -> PaymentResult.Executed(p.debitTransactionId, p.endToEndId)
            // Rejected after sending; wallet-pix reversed the debit.
            "REFUNDED" -> PaymentResult.Refused(p.reasonCode ?: "PIX_REFUNDED", null)
            else -> PaymentResult.Unknown("unexpected Pix status ${p.status}")
        }
    } catch (e: Exception) {
        PaymentErrors.toResult(e, "wallet-pix send")
    }

    private companion object {
        const val PIX_SEND = "pix:send"
    }
}
