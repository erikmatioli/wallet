package br.com.walletapp.api.adapter.out.otp

import br.com.walletapp.api.adapter.out.core.CoreTokens
import br.com.walletapp.api.application.port.DependencyUnavailableException
import br.com.walletapp.api.application.port.OtpCheck
import br.com.walletapp.api.application.port.OtpGateway
import br.com.walletapp.api.application.port.OtpPurpose
import br.com.walletapp.api.application.port.OtpSend
import br.com.walletapp.api.config.AppProperties
import br.com.walletapp.api.domain.Cpf
import br.com.walletapp.api.domain.Email
import org.springframework.http.HttpHeaders
import org.springframework.http.client.SimpleClientHttpRequestFactory
import org.springframework.stereotype.Component
import org.springframework.web.client.HttpClientErrorException
import org.springframework.web.client.HttpServerErrorException
import org.springframework.web.client.ResourceAccessException
import org.springframework.web.client.RestClient
import org.springframework.web.client.body

/**
 * wallet-otp, as the tenant that owns the app (ADR-002), with a wallet-core token down-scoped to otp:use.
 * Network failures and 5xx (including "the email could not be sent") become "try again"; a 4xx is
 * wallet-otp's answer.
 */
@Component
class OtpClient(builder: RestClient.Builder, props: AppProperties, private val tokens: CoreTokens) : OtpGateway {

    private val http: RestClient = builder
        .baseUrl(props.otp.baseUrl.toString())
        .requestFactory(SimpleClientHttpRequestFactory().apply {
            setConnectTimeout(props.otp.connectTimeout)
            setReadTimeout(props.otp.readTimeout)
        })
        .build()

    private data class CreateRequest(val subject: String, val purpose: String, val channel: String,
                                     val destination: String, val context: String?)
    private data class CreateResponse(val challengeId: String, val destinationMasked: String)
    private data class VerifyRequest(val subject: String, val code: String, val context: String?)
    private data class Problem(val code: String?, val retryAfterSeconds: Long?)

    override fun send(subject: Cpf, purpose: OtpPurpose, email: Email, context: String?): OtpSend = call("send") { token ->
        try {
            val r = http.post().uri("/v1/otp/challenges")
                .header(HttpHeaders.AUTHORIZATION, "Bearer $token")
                .body(CreateRequest(subject.digits, purpose.name, "EMAIL", email.value, context))
                .retrieve().body<CreateResponse>()!!
            OtpSend.Sent(r.challengeId, r.destinationMasked)
        } catch (e: HttpClientErrorException.TooManyRequests) {
            OtpSend.TooSoon(problem(e)?.retryAfterSeconds ?: 60)
        }
    }

    override fun verify(challengeId: String, subject: Cpf, code: String, context: String?): OtpCheck = call("verify") { token ->
        try {
            http.post().uri("/v1/otp/challenges/{id}/verify", challengeId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer $token")
                .body(VerifyRequest(subject.digits, code, context))
                .retrieve().toBodilessEntity()
            OtpCheck.Verified
        } catch (e: HttpClientErrorException) {
            if (e is HttpClientErrorException.Unauthorized || e is HttpClientErrorException.Forbidden) throw e
            OtpCheck.Refused(problem(e)?.code ?: "HTTP_${e.statusCode.value()}")
        }
    }

    private fun problem(e: HttpClientErrorException): Problem? =
        runCatching { e.getResponseBodyAs(Problem::class.java) }.getOrNull()

    private fun <T> call(operation: String, request: (String) -> T): T = try {
        tokens.withToken(SCOPE, request)
    } catch (e: HttpServerErrorException) {
        throw DependencyUnavailableException("wallet-otp $operation failed (${e.statusCode.value()})", e)
    } catch (e: ResourceAccessException) {
        throw DependencyUnavailableException("wallet-otp $operation failed: ${e.message}", e)
    }

    private companion object {
        const val SCOPE = "otp:use"
    }
}
