package br.com.walletapp.api.adapter.out

import br.com.walletapp.api.application.port.DependencyUnavailableException
import br.com.walletapp.api.application.port.PaymentNotConfirmedException
import org.springframework.web.client.ResourceAccessException
import org.springframework.web.client.RestClientException
import java.io.IOException

/**
 * Whether the answer was lost on the way: the service did not answer in time, or the connection broke. The
 * RestClient reports a read timeout either as a [ResourceAccessException] or, when it happens while the body is read,
 * as a plain [RestClientException] over the [IOException] (ADR-004): both are "try again", never a 500.
 */
internal fun lostAnswer(e: RestClientException): Boolean =
    e is ResourceAccessException || generateSequence<Throwable>(e) { it.cause }.any { it is IOException }

/**
 * A call that moves the customer's money (a transfer, a Pix): when its answer is lost, the payment may have been
 * made. The customer is told so, and confirming again repeats the same Idempotency-Key, which never pays twice.
 */
internal inline fun <T> payment(block: () -> T): T = try {
    block()
} catch (e: DependencyUnavailableException) {
    throw PaymentNotConfirmedException(e.message ?: "payment not confirmed", e)
}
