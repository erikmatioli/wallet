package br.com.walletapp.api.adapter.out.core

import br.com.walletapp.api.config.AppProperties
import org.springframework.http.client.SimpleClientHttpRequestFactory
import org.springframework.stereotype.Component
import org.springframework.web.client.HttpClientErrorException
import org.springframework.web.client.RestClient
import org.springframework.web.client.body
import java.time.Clock
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * The tenant's wallet-core tokens, per scope, cached until a minute before they expire. wallet-core is
 * the platform's identity provider: a token down-scoped to pix:send also opens wallet-pix's send API.
 * None of them ever leaves app-api.
 */
@Component
class CoreTokens(builder: RestClient.Builder, props: AppProperties, private val clock: Clock) {

    private val core = props.walletCore
    private val http: RestClient = builder
        .baseUrl(core.baseUrl.toString())
        .requestFactory(SimpleClientHttpRequestFactory().apply {
            setConnectTimeout(core.connectTimeout)
            setReadTimeout(core.readTimeout)
        })
        .build()
    private val cache = ConcurrentHashMap<String, Pair<String, Instant>>()

    private data class TokenResponse(val access_token: String, val expires_in: Long)

    /** [scope] null = every scope of the tenant. */
    fun token(scope: String? = null): String {
        val key = scope ?: "all"
        val now = clock.instant()
        cache[key]?.takeIf { now.isBefore(it.second) }?.let { return it.first }
        val spec = if (scope == null) http.post().uri("/v1/auth/token") else http.post().uri("/v1/auth/token?scope={s}", scope)
        val r = spec.headers { it.setBasicAuth(core.clientId, core.clientSecret) }.retrieve().body<TokenResponse>()!!
        cache[key] = r.access_token to now.plusSeconds(maxOf(0, r.expires_in - 60))
        return r.access_token
    }

    /** A 401 means the cached token expired early or the key rotated: once more with a fresh one. */
    fun <T> withToken(scope: String? = null, request: (String) -> T): T = try {
        request(token(scope))
    } catch (e: HttpClientErrorException.Unauthorized) {
        cache.remove(scope ?: "all")
        request(token(scope))
    }
}
