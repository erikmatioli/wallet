package br.com.walletscheduler.adapter.out.walletcore

import br.com.walletscheduler.config.SchedulerProperties
import org.springframework.http.client.SimpleClientHttpRequestFactory
import org.springframework.stereotype.Component
import org.springframework.web.client.HttpClientErrorException
import org.springframework.web.client.RestClient
import org.springframework.web.client.body
import java.time.Clock
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * wallet-core access tokens, per tenant and scope, cached until a minute before they expire. wallet-core
 * is the platform's identity provider: the same tokens open wallet-pix's send API (scope pix:send).
 */
@Component
class WalletCoreTokens(builder: RestClient.Builder, props: SchedulerProperties, private val clock: Clock) {

    private val http: RestClient = builder
        .baseUrl(props.walletCore.baseUrl.toString())
        .requestFactory(SimpleClientHttpRequestFactory().apply {
            setConnectTimeout(props.walletCore.connectTimeout)
            setReadTimeout(props.walletCore.readTimeout)
        })
        .build()
    private val credentials = props.tenants.associateBy { it.clientId }
    private val cache = ConcurrentHashMap<String, Cached>()

    private data class Cached(val value: String, val expiresAt: Instant)
    private data class TokenResponse(val access_token: String, val expires_in: Long)

    fun knows(clientId: String) = clientId in credentials

    /** [scope] null = every scope the tenant has; otherwise a token down-scoped to it (least privilege). */
    fun token(clientId: String, scope: String? = null): String {
        val key = "$clientId|${scope ?: "all"}"
        val now = clock.instant()
        cache[key]?.takeIf { now.isBefore(it.expiresAt) }?.let { return it.value }
        val c = credentials[clientId] ?: throw IllegalStateException("no wallet-core credentials for $clientId")
        // URI templates (not a builder lambda) so the client metric gets a real uri tag.
        val spec = if (scope == null) http.post().uri("/v1/auth/token") else http.post().uri("/v1/auth/token?scope={s}", scope)
        val response = spec.headers { it.setBasicAuth(c.clientId, c.clientSecret) }.retrieve().body<TokenResponse>()!!
        cache[key] = Cached(response.access_token, now.plusSeconds(maxOf(0, response.expires_in - 60)))
        return response.access_token
    }

    /** A 401 means the cached token expired early or the key rotated: get a fresh one, once. */
    fun <T> withToken(clientId: String, scope: String? = null, request: (String) -> T): T = try {
        request(token(clientId, scope))
    } catch (e: HttpClientErrorException.Unauthorized) {
        cache.remove("$clientId|${scope ?: "all"}")
        request(token(clientId, scope))
    }
}
