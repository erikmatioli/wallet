package br.com.walletcore.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.walletcore.application.port.in.BalanceHistoryUseCase;
import br.com.walletcore.application.port.in.BalanceHistoryUseCase.ClosingBalance;
import br.com.walletcore.application.port.in.MoveMoneyUseCase;
import br.com.walletcore.application.port.in.MoveMoneyUseCase.DepositCommand;
import br.com.walletcore.application.port.in.MoveMoneyUseCase.WithdrawCommand;
import br.com.walletcore.application.port.in.OnboardCustomerUseCase;
import br.com.walletcore.application.port.in.ProvisionTenantUseCase;
import br.com.walletcore.application.port.out.TransactionRunner;
import br.com.walletcore.domain.account.Account;
import br.com.walletcore.domain.exception.BusinessRuleException;
import br.com.walletcore.domain.exception.NotFoundException;
import br.com.walletcore.domain.shared.Money;
import br.com.walletcore.domain.shared.TenantId;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * ADR-012 against a real PostgreSQL: the V8 migration, the GREATEST in the balance UPDATE (a clock going back,
 * and many instances' clocks at once) and closing balances read through Row Level Security as wallet_app.
 */
@SpringBootTest
@ActiveProfiles("test")
class BalanceHistoryIntegrationTest {

    private static final ZoneId BRASILIA = ZoneId.of("America/Sao_Paulo");
    private static PostgreSQLContainer postgres;

    /** The core's clock, moved by the test. Thread-safe: the concurrency test reads it from many threads. */
    static final class TestClock extends Clock {
        volatile Instant now = Instant.parse("2026-10-08T13:00:00Z");

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }
    }

    @TestConfiguration
    static class Clocks {
        @Bean
        @Primary
        TestClock testClock() {
            return new TestClock();
        }
    }

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

    @Autowired TestClock clock;
    @Autowired ProvisionTenantUseCase provisionTenant;
    @Autowired OnboardCustomerUseCase onboardCustomer;
    @Autowired MoveMoneyUseCase moveMoney;
    @Autowired BalanceHistoryUseCase history;
    @Autowired JdbcClient jdbc;
    @Autowired TransactionRunner tx;

    private TenantId newTenant() {
        return provisionTenant.provision(new ProvisionTenantUseCase.Command(
                "t-" + UUID.randomUUID().toString().substring(0, 8), "a-strong-client-secret-123", "Test Tenant",
                "12345678", "0001", 4, null)).id();
    }

    private Account newAccount(TenantId tenant) {
        return onboardCustomer.onboard(new OnboardCustomerUseCase.Command(tenant, "Maria", "52998224725", null))
                .account();
    }

    private static Instant brasilia(String localDateTime) {
        return LocalDateTime.parse(localDateTime).atZone(BRASILIA).toInstant();
    }

    private void depositAt(TenantId tenant, Account a, long cents, Instant when) {
        clock.now = when;
        moveMoney.deposit(new DepositCommand(tenant, a.id(), Money.ofCents(cents), "d", "k-" + UUID.randomUUID()));
    }

    /** occurred_at of the account's entries, by sequence - read as the tenant, through Row Level Security. */
    private List<Instant> datesBySequence(TenantId tenant, Account a) {
        return tx.readOnly(tenant, () -> jdbc.sql(
                        "SELECT occurred_at FROM ledger_entry WHERE account_id = :a ORDER BY sequence_no")
                .param("a", a.id().value())
                .query((rs, i) -> rs.getObject(1, java.time.OffsetDateTime.class).toInstant()).list());
    }

    @Test
    void aClockThatGoesBackDoesNotPutAnEntryBeforeThePreviousOne() {
        TenantId tenant = newTenant();
        Account a = newAccount(tenant);
        Instant t = brasilia("2026-10-08T10:00:00");
        depositAt(tenant, a, 1_000, t);
        depositAt(tenant, a, 1_000, t.minusMillis(7)); // another instance, 7 ms late

        assertThat(datesBySequence(tenant, a)).containsExactly(t, t);
        Instant last = tx.readOnly(tenant, () -> jdbc.sql("SELECT last_occurred_at FROM account WHERE id = :a")
                .param("a", a.id().value())
                .query((rs, i) -> rs.getObject(1, java.time.OffsetDateTime.class).toInstant()).single());
        assertThat(last).isEqualTo(t);
    }

    @Test
    void concurrentPostingsWithClocksApartStillGoForwardInEveryAccount() throws Exception {
        TenantId tenant = newTenant();
        Account a = newAccount(tenant);
        depositAt(tenant, a, 1_000_000, brasilia("2026-10-08T09:00:00"));
        Instant base = brasilia("2026-10-08T11:00:00");

        List<Callable<Void>> tasks = new ArrayList<>();
        for (int i = 0; i < 60; i++) {
            final int n = i;
            tasks.add(() -> {
                // Each posting reads a clock up to 50 ms off; the test clock is shared, so set and post together.
                synchronized (clock) {
                    clock.now = base.plusMillis(ThreadLocalRandom.current().nextInt(-50, 50));
                }
                if (n % 2 == 0) {
                    moveMoney.deposit(new DepositCommand(tenant, a.id(), Money.ofCents(100), "in", "in-" + n));
                } else {
                    moveMoney.withdraw(new WithdrawCommand(tenant, a.id(), Money.ofCents(100), "out", "out-" + n));
                }
                return null;
            });
        }
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (var f : executor.invokeAll(tasks)) {
                f.get();
            }
        }

        List<Instant> dates = datesBySequence(tenant, a);
        assertThat(dates).hasSize(61);
        for (int i = 1; i < dates.size(); i++) {
            assertThat(dates.get(i)).as("entry %d", i + 1).isAfterOrEqualTo(dates.get(i - 1));
        }
        // And for the settlement shards too: every account the postings touched.
        Integer backwards = tx.readOnly(tenant, () -> jdbc.sql("""
                SELECT count(*) FROM ledger_entry e JOIN ledger_entry p
                  ON p.account_id = e.account_id AND p.sequence_no = e.sequence_no - 1
                 WHERE e.tenant_id = :t AND e.occurred_at < p.occurred_at""")
                .param("t", tenant.value()).query(Integer.class).single());
        assertThat(backwards).isZero();
    }

    @Test
    void closingBalancesThroughRowLevelSecurity() {
        TenantId tenant = newTenant();
        Account a = newAccount(tenant);
        depositAt(tenant, a, 10_000, brasilia("2026-10-08T10:00:00"));
        depositAt(tenant, a, 500, brasilia("2026-10-08T23:59:59.999"));
        depositAt(tenant, a, 70, brasilia("2026-10-09T00:00:00"));
        clock.now = brasilia("2026-10-09T12:00:00");

        ClosingBalance eighth = history.closingBalance(tenant, a.id(), LocalDate.parse("2026-10-08"));
        assertThat(eighth.balance().cents()).isEqualTo(10_500);
        assertThat(eighth.lastSequence()).isEqualTo(2);
        assertThatThrownBy(() -> history.closingBalance(tenant, a.id(), LocalDate.parse("2026-10-09")))
                .isInstanceOf(BusinessRuleException.class);

        clock.now = brasilia("2026-10-12T08:00:00");
        assertThat(history.dailyBalances(tenant, a.id(), LocalDate.parse("2026-10-07"), LocalDate.parse("2026-10-11")))
                .extracting(d -> d.balance().cents())
                .containsExactly(0L, 10_500L, 10_570L, 10_570L, 10_570L);

        // Another tenant does not see the account.
        TenantId other = newTenant();
        assertThatThrownBy(() -> history.closingBalance(other, a.id(), LocalDate.parse("2026-10-08")))
                .isInstanceOf(NotFoundException.class);
    }
}
