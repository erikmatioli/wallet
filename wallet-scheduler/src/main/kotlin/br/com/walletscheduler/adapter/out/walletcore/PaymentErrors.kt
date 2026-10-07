package br.com.walletscheduler.adapter.out.walletcore

import br.com.walletscheduler.application.port.PaymentResult
import org.springframework.web.client.HttpClientErrorException
import org.springframework.web.client.HttpServerErrorException
import org.springframework.web.client.ResourceAccessException

/**
 * How a failed payment call is read, the same for wallet-core and wallet-pix (both answer problem+json
 * with a stable `code`): a 4xx is the service's answer and nothing moved; a 5xx, 429 or network failure
 * leaves the outcome unknown, and the attempt is repeated with the same key.
 */
object PaymentErrors {

    private data class Problem(val code: String?, val detail: String?)

    fun toResult(e: Exception, operation: String): PaymentResult = when (e) {
        is HttpClientErrorException.TooManyRequests -> PaymentResult.Unknown("$operation throttled (429)")
        is HttpClientErrorException -> {
            val problem = runCatching { e.getResponseBodyAs(Problem::class.java) }.getOrNull()
            PaymentResult.Refused(problem?.code ?: "HTTP_${e.statusCode.value()}", problem?.detail)
        }
        is HttpServerErrorException -> PaymentResult.Unknown("$operation failed (${e.statusCode.value()})")
        is ResourceAccessException -> PaymentResult.Unknown("$operation unreachable: ${e.message}")
        else -> throw e
    }
}
