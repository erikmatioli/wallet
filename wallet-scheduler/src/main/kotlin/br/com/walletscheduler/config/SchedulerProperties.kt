package br.com.walletscheduler.config

import org.springframework.boot.context.properties.ConfigurationProperties
import java.net.URI
import java.time.Duration
import java.time.LocalTime

@ConfigurationProperties("scheduler")
data class SchedulerProperties(
    val walletCore: WalletCore,
    val pix: Pix,
    /** Credentials per tenant: on the day, each payment is made as the tenant that scheduled it. */
    val tenants: List<Tenant> = emptyList(),
    val windows: Windows = Windows(),
    val job: Job = Job(),
    val bus: Bus,
) {
    data class WalletCore(
        val baseUrl: URI,
        /** The API accepts wallet-core's own JWTs, checked against its JWKS. */
        val jwkSetUri: URI,
        val issuer: String,
        val connectTimeout: Duration = Duration.ofSeconds(2),
        val readTimeout: Duration = Duration.ofSeconds(5),
    )

    data class Pix(
        val baseUrl: URI,
        val connectTimeout: Duration = Duration.ofSeconds(2),
        val readTimeout: Duration = Duration.ofSeconds(10),
    )

    data class Tenant(val clientId: String, val clientSecret: String)

    data class Windows(
        val zone: String = "America/Sao_Paulo",
        val times: List<LocalTime> = listOf(LocalTime.of(6, 0), LocalTime.of(12, 0), LocalTime.of(18, 0)),
    )

    data class Job(
        val pollInterval: Duration = Duration.ofSeconds(30),
        val batchSize: Int = 50,
        val lease: Duration = Duration.ofMinutes(2),
        val unknownRetryIn: Duration = Duration.ofMinutes(1),
        val pixResultWait: Duration = Duration.ofMinutes(10),
    )

    /** SNS/SQS (LocalStack locally), where wallet-pix publishes the results of the Pix this service sends. */
    data class Bus(
        val enabled: Boolean = true,
        val endpoint: URI?,
        val region: String,
        val accessKey: String,
        val secretKey: String,
        val pixEventsQueue: String,
    )
}
