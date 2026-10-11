package br.com.walletcore.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.walletcore.application.port.in.MoveMoneyUseCase;
import br.com.walletcore.application.port.in.MoveMoneyUseCase.DepositCommand;
import br.com.walletcore.application.port.in.MoveMoneyUseCase.TreasuryTransferCommand;
import br.com.walletcore.application.port.in.MoveMoneyUseCase.WithdrawCommand;
import br.com.walletcore.application.port.in.OnboardCustomerUseCase;
import br.com.walletcore.application.port.in.ProvisionTenantUseCase;
import br.com.walletcore.application.port.in.TreasuryUseCase;
import br.com.walletcore.application.port.in.TreasuryUseCase.Position;
import br.com.walletcore.application.port.in.TreasuryUseCase.Statement;
import br.com.walletcore.application.port.in.TreasuryUseCase.UncoveredDay;
import br.com.walletcore.domain.account.Account;
import br.com.walletcore.domain.backing.BackingType;
import br.com.walletcore.domain.backing.TreasuryReference;
import br.com.walletcore.domain.exception.BusinessRuleException;
import br.com.walletcore.domain.ledger.TransactionType;
import br.com.walletcore.domain.shared.BackingAccountId;
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
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * ADR-011 against a real PostgreSQL, as wallet_app through Row Level Security: provisioning creates the backing
 * accounts, each rail posts to its own, the coverage limit holds under concurrency (the shards are locked), and the
 * treasury's reads (statement merged across shards, uncovered money per day) run on the real queries.
 */
@SpringBootTest
@ActiveProfiles("test")
class TreasuryIntegrationTest {

    private static final ZoneId BRASILIA = ZoneId.of("America/Sao_Paulo");
    private static PostgreSQLContainer postgres;

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
    @Autowired TreasuryUseCase treasury;

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

    private Position position(TenantId tenant, BackingType type) {
        return treasury.positions(tenant).stream().filter(p -> p.account().type() == type).findFirst().orElseThrow();
    }

    private void deposit(TenantId tenant, Account a, long cents) {
        moveMoney.deposit(new DepositCommand(tenant, a.id(), Money.ofCents(cents), "d", "k-" + UUID.randomUUID()));
    }

    @Test
    void railsPostToTheirBackingAccountsAndTheTreasuryCoversTheDay() {
        TenantId tenant = newTenant();
        Account a = newAccount(tenant);
        assertThat(treasury.positions(tenant)).extracting(p -> p.account().name())
                .containsExactly("Conta PI", "Caixa", "A regularizar");

        clock.now = brasilia("2026-10-08T10:00:00");
        for (int i = 0; i < 10; i++) {
            deposit(tenant, a, 1_000);                 // spread across the 4 shards of "a regularizar"
        }
        moveMoney.withdraw(new WithdrawCommand(tenant, a.id(), Money.ofCents(500), "", "w-" + UUID.randomUUID()));

        BackingAccountId unassigned = position(tenant, BackingType.UNASSIGNED).account().id();
        BackingAccountId reserves = treasury.register(new TreasuryUseCase.RegisterCommand(tenant, BackingType.RESERVES,
                "Reservas", "BC")).id();
        clock.now = brasilia("2026-10-09T17:00:00");
        moveMoney.treasuryTransfer(new TreasuryTransferCommand(tenant, unassigned, reserves, Money.ofCents(8_000), "",
                new TreasuryReference("TED 1", "resgate"), "c-1"));
        clock.now = brasilia("2026-10-10T12:00:00");

        assertThat(position(tenant, BackingType.UNASSIGNED).expected().cents()).isEqualTo(2_000);
        assertThat(position(tenant, BackingType.CASH).expected().cents()).isEqualTo(-500);
        assertThat(treasury.positions(tenant).stream().mapToLong(p -> p.expected().cents()).sum())
                .isEqualTo(9_500); // the customer's balance

        Statement page = treasury.statement(tenant, unassigned, null, 4);
        assertThat(page.entries().getFirst().entry().type()).isEqualTo(TransactionType.TREASURY_TRANSFER);
        assertThat(page.entries().getFirst().reference().document()).isEqualTo("TED 1");
        assertThat(page.entries().getFirst().otherBackingAccount().name()).isEqualTo("Reservas");
        assertThat(page.entries().get(1).customerName()).isEqualTo("Maria");
        assertThat(page.entries()).extracting(e -> e.expectedAfter().cents()).containsExactly(2_000L, 10_000L, 9_000L, 8_000L);
        Statement rest = treasury.statement(tenant, unassigned, page.next(), 50);
        assertThat(rest.entries()).hasSize(7);
        assertThat(rest.entries().getLast().expectedAfter().cents()).isEqualTo(1_000);
        assertThat(rest.next()).isNull();

        List<UncoveredDay> days = treasury.uncoveredDaily(tenant, LocalDate.parse("2026-10-08"),
                LocalDate.parse("2026-10-09"));
        assertThat(days).extracting(d -> d.created().cents(), d -> d.covered().cents(), d -> d.uncoveredAtEnd().cents())
                .containsExactly(org.assertj.core.groups.Tuple.tuple(10_000L, 0L, 10_000L),
                        org.assertj.core.groups.Tuple.tuple(0L, 8_000L, 2_000L));
        assertThat(position(tenant, BackingType.UNASSIGNED).expectedYesterday().cents()).isEqualTo(2_000);
    }

    @Test
    void twoCoveragesAtOnceCannotCoverMoreThanWasCreated() throws Exception {
        TenantId tenant = newTenant();
        Account a = newAccount(tenant);
        for (int i = 0; i < 8; i++) {
            deposit(tenant, a, 1_250);
        }
        BackingAccountId unassigned = position(tenant, BackingType.UNASSIGNED).account().id();
        BackingAccountId reserves = treasury.register(new TreasuryUseCase.RegisterCommand(tenant, BackingType.RESERVES,
                "Reservas", null)).id();

        List<Callable<Boolean>> tasks = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            String key = "c-" + i;
            tasks.add(() -> {
                try {
                    moveMoney.treasuryTransfer(new TreasuryTransferCommand(tenant, unassigned, reserves,
                            Money.ofCents(6_000), "", null, key));
                    return true;
                } catch (BusinessRuleException e) {
                    return false;
                }
            });
        }
        int succeeded = 0;
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (Future<Boolean> f : executor.invokeAll(tasks)) {
                try {
                    succeeded += f.get() ? 1 : 0;
                } catch (ExecutionException e) {
                    throw new AssertionError(e.getCause());
                }
            }
        }
        assertThat(succeeded).isEqualTo(1);
        assertThat(position(tenant, BackingType.UNASSIGNED).expected().cents()).isEqualTo(4_000);
    }
}
