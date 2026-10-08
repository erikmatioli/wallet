package br.com.walletapp.api

import br.com.walletapp.contract.AppInfo
import kotlinx.serialization.json.Json
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.DockerClientFactory
import org.testcontainers.postgresql.PostgreSQLContainer
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

/**
 * The skeleton boots on a real PostgreSQL - Spring Boot 4 built by Gradle - and answers the contract the
 * desktop reads, decoded here with kotlinx.serialization exactly as the desktop will.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
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

    private fun get(path: String): HttpResponse<String> = HttpClient.newHttpClient().send(
        HttpRequest.newBuilder(URI.create("http://localhost:$port$path")).GET().build(),
        HttpResponse.BodyHandlers.ofString(),
    )

    @Test
    fun `starts and reports UP`() {
        val health = get("/actuator/health")
        assertThat(health.statusCode()).isEqualTo(200)
        assertThat(health.body()).contains("\"status\":\"UP\"")
    }

    @Test
    fun `answers the app contract`() {
        val info = Json.decodeFromString<AppInfo>(get("/app/v1/info").body())
        assertThat(info.name).isEqualTo("app-api")
        assertThat(info.tenant).isEqualTo("demo-tenant") // the tenant this instance is configured for
    }
}
