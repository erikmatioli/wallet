package br.com.walletotp

import br.com.walletotp.application.RecordingSender
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
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.Executors

/**
 * The real wiring against a real PostgreSQL: the API with wallet-core-style JWTs, the SQL of the limits and
 * the locks. Only the email is a fake (the SMTP adapter is a few lines over Spring's JavaMailSender).
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["otp.code-key=test-key-0123456789abcdef0123456789"],
)
class OtpIntegrationTest {

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
        fun recordingSender() = RecordingSender()

        /** Plays wallet-core's JWKS: the tests sign their own tokens with this key. */
        @Bean
        @Primary
        fun testJwtDecoder(): JwtDecoder = NimbusJwtDecoder.withPublicKey(key.toRSAPublicKey()).build()
    }

    @LocalServerPort private var port = 0
    @Autowired private lateinit var sender: RecordingSender
    @Autowired private lateinit var jdbc: JdbcClient

    private val http = HttpClient.newHttpClient()
    private val encoder = NimbusJwtEncoder(ImmutableJWKSet<SecurityContext>(JWKSet(key)))
    private val tenant = UUID.randomUUID()

    @BeforeEach
    fun clean() {
        sender.sent.clear()
        sender.failing = false
    }

    private fun token(tenantId: UUID = tenant, scope: String = "otp:use"): String =
        encoder.encode(JwtEncoderParameters.from(JwtClaimsSet.builder()
            .issuer("wallet-core").subject("demo-tenant")
            .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(600))
            .claim("tenant_id", tenantId.toString()).claim("scope", scope)
            .build())).tokenValue

    private fun post(path: String, body: String, token: String = token()): HttpResponse<String> =
        http.send(HttpRequest.newBuilder(URI.create("http://localhost:$port$path"))
            .header("Authorization", "Bearer $token").header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString())

    private fun create(subject: String, email: String = "maria@example.com", purpose: String = "LOGIN", token: String = token()) =
        post("/v1/otp/challenges", """{"subject":"$subject","purpose":"$purpose","destination":"$email"}""", token)

    private fun verify(id: String, subject: String, code: String, token: String = token()) =
        post("/v1/otp/challenges/$id/verify", """{"subject":"$subject","code":"$code"}""", token)

    private fun idOf(response: HttpResponse<String>) = Regex("\"challengeId\":\"([^\"]+)\"").find(response.body())!!.groupValues[1]

    @Test
    fun `a code goes out by email and verifies once over the API`() {
        val created = create("52998224725")
        assertThat(created.statusCode()).isEqualTo(201)
        assertThat(created.body()).contains("\"destinationMasked\":\"m***@example.com\"")
        val id = idOf(created)

        val wrong = verify(id, "52998224725", "000000")
        assertThat(wrong.statusCode()).isEqualTo(422)
        assertThat(wrong.body()).contains("\"code\":\"INVALID_CODE\"").contains("\"attemptsLeft\":4")

        assertThat(verify(id, "52998224725", sender.lastCode()).body()).isEqualTo("""{"verified":true}""")
        assertThat(verify(id, "52998224725", sender.lastCode()).body()).contains("CHALLENGE_ALREADY_USED")

        // Only hashes and the masked email in the database.
        val row = jdbc.sql("SELECT * FROM challenge WHERE id = :id").param("id", UUID.fromString(id))
            .query { rs, _ -> listOf(rs.getString("code_hash"), rs.getString("destination_masked"), rs.getString("destination_hash")) }
            .single()
        assertThat(row.joinToString()).doesNotContain(sender.lastCode()).doesNotContain("maria@example.com")
    }

    @Test
    fun `too soon is 429 with Retry-After, and another tenant never sees the challenge`() {
        val created = create("11144477735")
        val again = create("11144477735")
        assertThat(again.statusCode()).isEqualTo(429)
        assertThat(again.headers().firstValue("Retry-After")).isPresent
        assertThat(again.body()).contains("\"retryAfterSeconds\":")

        val other = token(tenantId = UUID.randomUUID())
        assertThat(verify(idOf(created), "11144477735", sender.lastCode(), other).statusCode()).isEqualTo(404)
    }

    @Test
    fun `without otp use there is no API, and nothing works without a token`() {
        assertThat(create("39053344705", token = token(scope = "accounts:read")).statusCode()).isEqualTo(403)
        val anonymous = http.send(HttpRequest.newBuilder(URI.create("http://localhost:$port/v1/otp/challenges"))
            .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString("{}")).build(),
            HttpResponse.BodyHandlers.ofString())
        assertThat(anonymous.statusCode()).isEqualTo(401)
    }

    @Test
    fun `two requests at once for the same subject send one code`() {
        val pool = Executors.newFixedThreadPool(6)
        val statuses = pool.invokeAll((1..6).map { Callable { create("12345678909", email = "joao@example.com").statusCode() } })
            .map { it.get() }
        pool.shutdown()

        assertThat(statuses.count { it == 201 }).isEqualTo(1)
        assertThat(statuses.count { it == 429 }).isEqualTo(5)
        assertThat(sender.sent.count { it.destination == "joao@example.com" }).isEqualTo(1)
    }

    @Test
    fun `a down email server is 503 and the code does not count for the limits`() {
        sender.failing = true
        assertThat(create("98765432100").let { it.statusCode() to it.body() })
            .satisfies({ assertThat(it.first).isEqualTo(503); assertThat(it.second).contains("CODE_NOT_SENT") })

        sender.failing = false
        assertThat(create("98765432100").statusCode()).isEqualTo(201)
    }
}
