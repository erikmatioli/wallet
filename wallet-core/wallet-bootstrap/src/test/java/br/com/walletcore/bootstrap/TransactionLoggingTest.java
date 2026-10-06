package br.com.walletcore.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.walletcore.application.port.in.OnboardCustomerUseCase;
import br.com.walletcore.application.port.in.ProvisionTenantUseCase;
import br.com.walletcore.domain.account.Account;
import br.com.walletcore.domain.shared.TenantId;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Every money movement leaves log lines that can be filtered by tenant: one on the request thread
 * (with the request's trace) and one when the outbox relay publishes its event - on a scheduler
 * thread, where the tenant only reaches the MDC because the relay puts it there.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class TransactionLoggingTest {

    private static final String CLIENT_SECRET = "a-strong-client-secret-123";
    private static PostgreSQLContainer postgres;

    @BeforeAll
    static void requireDocker() {
        Assumptions.assumeTrue(DockerClientFactory.instance().isDockerAvailable(), "Docker is not available");
        postgres = new PostgreSQLContainer("postgres:17-alpine").withInitScript("init-roles.sql");
        postgres.start();
    }

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> postgres.getJdbcUrl());
        registry.add("spring.datasource.username", () -> "wallet_app");
        registry.add("spring.datasource.password", () -> "wallet_app");
        registry.add("spring.flyway.url", () -> postgres.getJdbcUrl());
        registry.add("spring.flyway.user", () -> postgres.getUsername());
        registry.add("spring.flyway.password", () -> postgres.getPassword());
    }

    @LocalServerPort int port;
    @Autowired ProvisionTenantUseCase provisionTenant;
    @Autowired OnboardCustomerUseCase onboardCustomer;

    private final HttpClient http = HttpClient.newHttpClient();
    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();
    private final List<Logger> watched = List.of(
            (Logger) LoggerFactory.getLogger("br.com.walletcore.adapter.in.rest.AccountController"),
            (Logger) LoggerFactory.getLogger("br.com.walletcore.adapter.out.messaging.LoggingEventPublisher"));

    @BeforeEach
    void captureLogs() {
        logs.start();
        watched.forEach(l -> l.addAppender(logs));
    }

    @AfterEach
    void stopCapturing() {
        watched.forEach(l -> l.detachAppender(logs));
    }

    @Test
    void aDepositIsLoggedWithItsTenantOnTheRequestAndWhenItsEventIsPublished() throws Exception {
        String clientId = "t-" + UUID.randomUUID().toString().substring(0, 8);
        TenantId tenant = provisionTenant.provision(new ProvisionTenantUseCase.Command(clientId, CLIENT_SECRET,
                "Log Tenant", "12345678", "0001", 2, null)).id();
        Account account = onboardCustomer.onboard(new OnboardCustomerUseCase.Command(tenant, "Log Customer",
                "52998224725", null)).account();

        HttpResponse<String> deposit = http.send(HttpRequest.newBuilder(url("/v1/accounts/" + account.id().value()
                        + "/deposits"))
                .header("Authorization", "Bearer " + token(clientId))
                .header("Idempotency-Key", "log-" + UUID.randomUUID())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"amount\": 12.34, \"description\": \"log test\"}"))
                .build(), HttpResponse.BodyHandlers.ofString());
        assertThat(deposit.statusCode()).isEqualTo(201);
        String transactionId = field(deposit.body(), "id");

        ILoggingEvent posted = awaitLog("transaction posted: id=" + transactionId);
        assertThat(posted.getMDCPropertyMap()).containsEntry("tenant_id", tenant.value().toString());
        assertThat(posted.getFormattedMessage()).contains("type=DEPOSIT", "amount=12.34");

        ILoggingEvent published = awaitLog("aggregate=transaction/" + transactionId);
        assertThat(published.getThreadName()).as("published by the relay, not the request")
                .isNotEqualTo(posted.getThreadName());
        assertThat(published.getMDCPropertyMap()).containsEntry("tenant_id", tenant.value().toString());
    }

    private ILoggingEvent awaitLog(String fragment) throws InterruptedException {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(10));
        while (Instant.now().isBefore(deadline)) {
            Optional<ILoggingEvent> found = List.copyOf(logs.list).stream()
                    .filter(e -> e.getFormattedMessage().contains(fragment))
                    .findFirst();
            if (found.isPresent()) {
                return found.get();
            }
            Thread.sleep(100);
        }
        throw new AssertionError("no log line containing: " + fragment);
    }

    private String token(String clientId) throws Exception {
        String basic = Base64.getEncoder().encodeToString((clientId + ":" + CLIENT_SECRET)
                .getBytes(StandardCharsets.UTF_8));
        HttpResponse<String> response = http.send(HttpRequest.newBuilder(url("/v1/auth/token"))
                .header("Authorization", "Basic " + basic)
                .POST(HttpRequest.BodyPublishers.noBody())
                .build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        return field(response.body(), "access_token");
    }

    private URI url(String path) {
        return URI.create("http://localhost:" + port + path);
    }

    /** First string value of {@code name} in a flat JSON body - enough for these two responses. */
    private static String field(String json, String name) {
        Matcher m = Pattern.compile("\"" + name + "\"\\s*:\\s*\"([^\"]+)\"").matcher(json);
        assertThat(m.find()).as(name + " in " + json).isTrue();
        return m.group(1);
    }
}
