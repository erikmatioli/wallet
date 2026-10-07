package br.com.walletscheduler

import br.com.walletscheduler.adapter.`in`.job.ExecutionJob
import br.com.walletscheduler.application.ExecutionService
import br.com.walletscheduler.application.FakePix
import br.com.walletscheduler.application.FakeWalletCore
import br.com.walletscheduler.application.MutableClock
import br.com.walletscheduler.application.ScheduleService
import br.com.walletscheduler.application.port.PaymentResult
import br.com.walletscheduler.application.port.PixOutcome
import br.com.walletscheduler.domain.ExecutionWindows
import br.com.walletscheduler.domain.TenantId
import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.jwk.RSAKey
import com.nimbusds.jose.jwk.source.ImmutableJWKSet
import com.nimbusds.jose.proc.SecurityContext
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.security.oauth2.jwt.JwtClaimsSet
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.oauth2.jwt.JwtEncoderParameters
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.DockerClientFactory
import org.testcontainers.postgresql.PostgreSQLContainer
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.Executors

/**
 * The real wiring against a real PostgreSQL: the API with wallet-core-style JWTs, the job end to end,
 * and two workers claiming at once. Only wallet-core is a fake (it has its own tests) and the clock is
 * moved by hand to reach the payment day.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    // The tests drive the job themselves, and hand Pix events to the service directly (no SQS here).
    properties = ["scheduler.job.poll-interval=1h", "scheduler.bus.enabled=false"],
)
class SchedulerIntegrationTest {

    companion object {
        private lateinit var postgres: PostgreSQLContainer
        private val key: RSAKey = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
            .let { RSAKey.Builder(it.public as RSAPublicKey).privateKey(it.private as RSAPrivateKey).build() }

        @JvmStatic
        @BeforeAll
        fun requireDocker() {
            assumeTrue(DockerClientFactory.instance().isDockerAvailable, "Docker is not available")
            postgres = PostgreSQLContainer("postgres:17-alpine").also { it.start() }
        }

        @JvmStatic
        @DynamicPropertySource
        fun database(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url") { postgres.jdbcUrl }
            registry.add("spring.datasource.username") { postgres.username }
            registry.add("spring.datasource.password") { postgres.password }
            registry.add("spring.flyway.url") { postgres.jdbcUrl }
            registry.add("spring.flyway.user") { postgres.username }
            registry.add("spring.flyway.password") { postgres.password }
        }
    }

    @TestConfiguration
    class Fakes {
        @Bean
        @Primary
        fun fakeWalletCore() = FakeWalletCore()

        @Bean
        @Primary
        fun fakePix() = FakePix()

        @Bean
        @Primary
        fun testClock() = MutableClock(Instant.parse("2026-10-07T13:00:00Z")) // 10:00 in Brasília

        /** Plays wallet-core's JWKS: the tests sign their own tokens with this key. */
        @Bean
        @Primary
        fun testJwtDecoder(): JwtDecoder = NimbusJwtDecoder.withPublicKey(key.toRSAPublicKey()).build()
    }

    @LocalServerPort private var port = 0
    @Autowired private lateinit var walletCore: FakeWalletCore
    @Autowired private lateinit var pix: FakePix
    @Autowired private lateinit var clock: MutableClock
    @Autowired private lateinit var job: ExecutionJob
    @Autowired private lateinit var executions: ExecutionService
    @Autowired private lateinit var schedules: ScheduleService
    @Autowired private lateinit var windows: ExecutionWindows
    @Autowired private lateinit var jdbc: JdbcClient

    private val http = HttpClient.newHttpClient()
    private val encoder = NimbusJwtEncoder(ImmutableJWKSet<SecurityContext>(JWKSet(key)))
    private val tenant = UUID.randomUUID()

    /** The context (and so the clock) is shared by every test: each one starts on 2026-10-07, 10:00 in Brasília. */
    @BeforeEach
    fun resetClock() {
        clock.now = Instant.parse("2026-10-07T13:00:00Z")
        walletCore.answers.clear()
        pix.answers.clear()
    }

    private fun token(tenantId: UUID = tenant, scope: String = "schedules:read schedules:write"): String =
        encoder.encode(JwtEncoderParameters.from(JwtClaimsSet.builder()
            .issuer("wallet-core").subject("demo-tenant")
            .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(600))
            .claim("tenant_id", tenantId.toString()).claim("scope", scope)
            .build())).tokenValue

    private fun call(method: String, path: String, body: String? = null, token: String = token(),
                     key: String? = null): HttpResponse<String> {
        val request = HttpRequest.newBuilder(URI.create("http://localhost:$port$path"))
            .header("Authorization", "Bearer $token")
            .header("Content-Type", "application/json")
            .method(method, body?.let { HttpRequest.BodyPublishers.ofString(it) } ?: HttpRequest.BodyPublishers.noBody())
        key?.let { request.header("Idempotency-Key", it) }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString())
    }

    private fun createBody(payer: UUID, payeeNumber: String, executeOn: String = "2026-10-08", amount: String = "150.00") =
        """{"payerAccountId":"$payer","executeOn":"$executeOn","amount":$amount,"description":"aluguel",
           "destination":{"branch":"0001","number":"$payeeNumber","checkDigit":"5"}}"""

    @Test
    fun `api creates, replays, lists and isolates schedules by tenant and scope`() {
        val payer = walletCore.account("Maria", "1100001")
        val payee = walletCore.account("João", "2200002")

        val created = call("POST", "/v1/schedules", createBody(payer.id.value, payee.number), key = "k-api")
        assertThat(created.statusCode()).isEqualTo(201)
        assertThat(created.body()).contains("\"status\":\"ACTIVE\"", "\"holderName\":\"João\"", "\"amount\":150.00")
        val id = Regex("\"id\":\"([^\"]+)\"").find(created.body())!!.groupValues[1]

        assertThat(call("POST", "/v1/schedules", createBody(payer.id.value, payee.number), key = "k-api").statusCode())
            .isEqualTo(200)
        assertThat(call("GET", "/v1/schedules?payerAccountId=${payer.id}").body()).contains(id)

        // Another tenant: as if it did not exist.
        assertThat(call("GET", "/v1/schedules/$id", token = token(tenantId = UUID.randomUUID())).statusCode())
            .isEqualTo(404)
        // A read-only token cannot create or cancel.
        assertThat(call("POST", "/v1/schedules/$id/cancel", token = token(scope = "schedules:read")).statusCode())
            .isEqualTo(403)
        // Validation and business errors come back as problem+json with a code.
        val past = call("POST", "/v1/schedules", createBody(payer.id.value, payee.number, executeOn = "2026-10-07"),
            key = "k-past")
        assertThat(past.statusCode()).isEqualTo(400)
        assertThat(past.body()).contains("\"code\":\"INVALID_EXECUTION_DATE\"")
    }

    @Test
    fun `on the day the job pays and the api shows the attempts`() {
        val payer = walletCore.account("Maria", "1300001")
        val payee = walletCore.account("João", "2300002")
        val created = call("POST", "/v1/schedules", createBody(payer.id.value, payee.number), key = "k-job")
        assertThat(created.statusCode()).isEqualTo(201)
        val id = Regex("\"id\":\"([^\"]+)\"").find(created.body())!!.groupValues[1]
        walletCore.answers += PaymentResult.Refused("INSUFFICIENT_FUNDS", "no money")

        clock.now = windows.first(LocalDate.of(2026, 10, 8))
        job.run()

        val detail = call("GET", "/v1/schedules/$id").body()
        assertThat(detail).contains("\"status\":\"PENDING\"", "\"code\":\"INSUFFICIENT_FUNDS\"",
            "Saldo insuficiente no momento do pagamento", "\"outcome\":\"REFUSED\"")

        walletCore.answers.clear()
        clock.now = windows.nextAfter(LocalDate.of(2026, 10, 8), clock.now)!!
        job.run()

        val paid = call("GET", "/v1/schedules/$id").body()
        assertThat(paid).contains("\"status\":\"COMPLETED\"", "\"status\":\"EXECUTED\"", "\"outcome\":\"EXECUTED\"")
    }

    @Test
    fun `two workers claiming at once never take the same execution`() {
        val tenantId = TenantId(UUID.randomUUID())
        val payer = walletCore.account("Maria", "1400001")
        val payee = walletCore.account("João", "2400002")
        val ids = (1..20).map {
            schedules.createTransfer(ScheduleService.CreateTransfer(
                ScheduleService.Common(tenantId, "demo-tenant", payer.id, 1_000, null, LocalDate.of(2026, 10, 9), "k-$it"),
                "0001", payee.number, "5")).details.execution.id
        }

        clock.now = windows.first(LocalDate.of(2026, 10, 9))
        val claimed = Executors.newVirtualThreadPerTaskExecutor().use { pool ->
            pool.invokeAll(List(4) { Callable { executions.claim() } }).flatMap { it.get() }
        }.map { it.execution.id }.filter { it in ids }

        assertThat(claimed).doesNotHaveDuplicates().containsExactlyInAnyOrderElementsOf(ids)
        val attempts = jdbc.sql("SELECT count(*) FROM schedule_attempt WHERE execution_id IN (:ids)")
            .param("ids", ids.map { it.value }).query(Long::class.java).single()
        assertThat(attempts).isEqualTo(20)
    }

    @Test
    fun `a Pix schedule is stored, sent on the day and closed by its event`() {
        val payer = walletCore.account("Maria", "1500001").also { walletCore.holders[it.id] = "52998224725" }
        val body = """{"type":"PIX","payerAccountId":"${payer.id}","executeOn":"2026-10-08","amount":42.50,
            "pix":{"payerTaxId":"529.982.247-25","payee":{"ispb":"99999999","branch":"0042","accountNumber":"12345678",
            "taxId":"111.444.777-35","name":"Fulano Externo"}}}"""

        val created = call("POST", "/v1/schedules", body, key = "k-pix")
        assertThat(created.statusCode()).isEqualTo(201)
        assertThat(created.body()).contains("\"type\":\"PIX\"", "\"taxIdMasked\":\"***7735\"", "\"destination\":null")
        assertThat(created.body()).doesNotContain("11144477735") // the full CPF never leaves the service
        val id = Regex("\"id\":\"([^\"]+)\"").find(created.body())!!.groupValues[1]

        clock.now = windows.first(LocalDate.of(2026, 10, 8))
        job.run()
        val key = jdbc.sql("SELECT a.idempotency_key FROM schedule_attempt a JOIN schedule_execution e ON e.id = a.execution_id WHERE e.schedule_id = :id")
            .param("id", UUID.fromString(id)).query(String::class.java).single()
        val pending = pix.pendingOf(key)
        assertThat(call("GET", "/v1/schedules/$id").body())
            .contains("\"status\":\"PROCESSING\"", "\"endToEndId\":\"${pending.endToEndId}\"")

        executions.onPixEvent(PixOutcome(key, settled = true, reasonCode = null))

        assertThat(call("GET", "/v1/schedules/$id").body())
            .contains("\"status\":\"COMPLETED\"", "\"status\":\"EXECUTED\"", "\"transactionId\":\"${pending.transactionId}\"")
    }
}
