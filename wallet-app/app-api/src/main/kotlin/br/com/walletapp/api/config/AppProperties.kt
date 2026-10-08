package br.com.walletapp.api.config

import org.springframework.boot.context.properties.ConfigurationProperties
import java.net.URI
import java.time.Duration

@ConfigurationProperties("app")
data class AppProperties(val session: Session, val walletCore: WalletCore, val pix: Pix, val scheduler: Scheduler) {

    /**
     * @property secret the HS256 key of the customers' tokens: at least 32 bytes, only in app-api's
     *                  configuration, never in the desktop. It has no default: every instance (one per
     *                  tenant) must be given its own, so two tenants' apps never share a key by accident.
     */
    data class Session(val secret: String, val ttl: Duration = Duration.ofMinutes(30)) {
        init {
            require(secret.toByteArray().size >= 32) { "app.session.secret must have at least 32 bytes" }
        }
    }

    /** The tenant that owns the app: app-api calls wallet-core as it (ADR-001, decision 1). */
    data class WalletCore(
        val baseUrl: URI,
        val clientId: String,
        val clientSecret: String,
        val connectTimeout: Duration = Duration.ofSeconds(2),
        val readTimeout: Duration = Duration.ofSeconds(5),
    )

    /** wallet-scheduler's API, called as the same tenant. */
    data class Scheduler(
        val baseUrl: URI,
        val connectTimeout: Duration = Duration.ofSeconds(2),
        val readTimeout: Duration = Duration.ofSeconds(10),
    )

    /** wallet-pix's send API, called as the same tenant. */
    data class Pix(
        val baseUrl: URI,
        val connectTimeout: Duration = Duration.ofSeconds(2),
        val readTimeout: Duration = Duration.ofSeconds(10),
    )
}

/**
 * The claims of the customer's token, shared by the issuer and the API that reads them. Each app-api
 * instance serves one tenant (ADR-001, decision 1): its tokens name that tenant, in the issuer and in
 * [TENANT], and it accepts no other - not even one signed with the same key by mistake.
 */
object SessionClaims {
    const val ACCOUNT_ID = "account_id"
    const val TENANT = "tenant"

    fun issuer(tenant: String) = "app-api:$tenant"
}
