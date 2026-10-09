package br.com.walletotp

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.DockerClientFactory
import org.testcontainers.postgresql.PostgreSQLContainer
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

/** The service boots on a real PostgreSQL and reports itself healthy (no SMTP server needed: see application.yml). */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = ["otp.code-key=test-key-0123456789abcdef0123456789"])
class ApplicationStartupTest {

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

    @LocalServerPort
    private var port: Int = 0

    @Test
    fun `starts and reports UP`() {
        val response = HttpClient.newHttpClient().send(
            HttpRequest.newBuilder(URI.create("http://localhost:$port/actuator/health")).GET().build(),
            HttpResponse.BodyHandlers.ofString(),
        )

        assertThat(response.statusCode()).isEqualTo(200)
        assertThat(response.body()).contains("\"status\":\"UP\"")
    }
}
