package br.com.walletapp.api

import br.com.walletapp.api.application.FakeCore
import br.com.walletapp.api.application.FakeOtp
import br.com.walletapp.api.application.port.CoreBanking
import br.com.walletapp.api.domain.AccountId
import br.com.walletapp.contract.AppError
import br.com.walletapp.contract.CodeSent
import br.com.walletapp.contract.Me
import br.com.walletapp.contract.Session
import kotlinx.serialization.json.Json
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Bean
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Primary
import org.springframework.security.oauth2.jose.jws.MacAlgorithm
import org.springframework.security.oauth2.jwt.JwsHeader
import org.springframework.security.oauth2.jwt.JwtClaimsSet
import org.springframework.security.oauth2.jwt.JwtEncoder
import org.springframework.security.oauth2.jwt.JwtEncoderParameters
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.DockerClientFactory
import org.testcontainers.postgresql.PostgreSQLContainer
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Instant
import java.util.UUID

/**
 * The real API on a real PostgreSQL, with the real tokens - only wallet-core and wallet-otp are fakes (each
 * has its own tests). What matters most here is ADR-001's rule: a customer only ever reaches their own
 * account; and ADR-002's: the code sent by email is the only way in.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class AppApiIntegrationTest {

    companion object {
        private lateinit var postgres: PostgreSQLContainer

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

    /** Answers /me with the account it was asked for, so the test sees which account a token reached. */
    class AccountEchoCore : CoreBanking by FakeCore() {
        override fun me(accountId: AccountId) = Me("Dono de $accountId", "0001", "100", "1", "ACTIVE", 0)
    }

    @TestConfiguration
    class Fakes {
        @Bean
        @Primary
        fun fakeCore(): CoreBanking = AccountEchoCore()

        @Bean
        @Primary
        fun fakeOtp() = FakeOtp()
    }

    @LocalServerPort
    private var port = 0

    @Autowired
    private lateinit var encoder: JwtEncoder

    @Autowired
    private lateinit var otp: FakeOtp
    private val http = HttpClient.newHttpClient()

    private fun call(method: String, path: String, body: String? = null, token: String? = null): HttpResponse<String> {
        val r = HttpRequest.newBuilder(URI.create("http://localhost:$port$path")).header("Content-Type", "application/json")
            .method(method, body?.let { HttpRequest.BodyPublishers.ofString(it) } ?: HttpRequest.BodyPublishers.noBody())
        token?.let { r.header("Authorization", "Bearer $it") }
        return http.send(r.build(), HttpResponse.BodyHandlers.ofString())
    }

    private fun signup(cpf: String, name: String, email: String = "cliente@example.com"): Session {
        val started = call("POST", "/app/v1/signup/start", """{"cpf":"$cpf","name":"$name","email":"$email"}""")
        assertThat(started.statusCode()).isEqualTo(200)
        val id = Json.decodeFromString<CodeSent>(started.body()).challengeId
        val r = call("POST", "/app/v1/signup/confirm",
            """{"challengeId":"$id","code":"${otp.last().code}","cpf":"$cpf","name":"$name","email":"$email"}""")
        assertThat(r.statusCode()).isEqualTo(201)
        return Json.decodeFromString(r.body())
    }

    @Test
    fun `each token only reaches its own account, and nothing works without one`() {
        val maria = signup("529.982.247-25", "Maria Silva")
        val joao = signup("111.444.777-35", "João Souza")

        val mariaMe = Json.decodeFromString<Me>(call("GET", "/app/v1/me", token = maria.token).body())
        val joaoMe = Json.decodeFromString<Me>(call("GET", "/app/v1/me", token = joao.token).body())
        assertThat(mariaMe.customerName).isNotEqualTo(joaoMe.customerName)

        val noToken = call("GET", "/app/v1/me")
        assertThat(noToken.statusCode()).isEqualTo(401)
        assertThat(Json.decodeFromString<AppError>(noToken.body()).code).isEqualTo("SESSION_EXPIRED")

        // Changing one character of the signature: the token is not ours any more.
        val tampered = maria.token.dropLast(1) + (if (maria.token.last() == 'A') 'B' else 'A')
        assertThat(call("GET", "/app/v1/me", token = tampered).statusCode()).isEqualTo(401)
    }

    /**
     * Two tenants' apps sharing a key by mistake: the signature checks out, but the token says it is
     * another tenant's - refused before anything is called.
     */
    @Test
    fun `a token of another tenant's app is refused even when it is signed with the same key`() {
        val claims = { tenant: String ->
            JwtClaimsSet.builder()
                .issuer("app-api:$tenant").subject(UUID.randomUUID().toString())
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(600))
                .claim("account_id", UUID.randomUUID().toString()).claim("tenant", tenant)
                .build()
        }
        val header = JwsHeader.with(MacAlgorithm.HS256).build()
        val foreign = encoder.encode(JwtEncoderParameters.from(header, claims("segundo-tenant"))).tokenValue
        val own = encoder.encode(JwtEncoderParameters.from(header, claims("demo-tenant"))).tokenValue

        assertThat(call("GET", "/app/v1/me", token = foreign).statusCode()).isEqualTo(401)
        assertThat(call("GET", "/app/v1/me", token = own).statusCode()).isEqualTo(200) // same key, right tenant
    }

    @Test
    fun `login with the code from the email, and the contract's errors`() {
        signup("390.533.447-05", "Ana Lima", "ana@example.com")

        val started = call("POST", "/app/v1/login/start", """{"cpf":"39053344705"}""")
        assertThat(started.statusCode()).isEqualTo(200)
        val id = Json.decodeFromString<CodeSent>(started.body()).challengeId
        assertThat(otp.last().email).isEqualTo("ana@example.com")

        val wrong = call("POST", "/app/v1/login/confirm", """{"challengeId":"$id","cpf":"39053344705","code":"000000"}""")
        assertThat(wrong.statusCode()).isEqualTo(401)
        assertThat(Json.decodeFromString<AppError>(wrong.body()).message).isEqualTo("Código incorreto. Confira o e-mail e tente de novo.")

        val ok = call("POST", "/app/v1/login/confirm", """{"challengeId":"$id","cpf":"39053344705","code":"${otp.last().code}"}""")
        assertThat(ok.statusCode()).isEqualTo(200)
        assertThat(Json.decodeFromString<Session>(ok.body()).customerName).isEqualTo("Ana Lima")

        val tooSoon = call("POST", "/app/v1/login/start", """{"cpf":"39053344705"}""")
        assertThat(tooSoon.statusCode()).isEqualTo(429)
        assertThat(tooSoon.headers().firstValue("Retry-After")).isPresent
        assertThat(Json.decodeFromString<AppError>(tooSoon.body()).retryAfterSeconds).isPositive()
    }
}
