package br.com.walletcore.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.walletcore.application.port.in.AuditLedgerUseCase;
import br.com.walletcore.application.port.in.MoveMoneyUseCase;
import br.com.walletcore.application.port.in.MoveMoneyUseCase.DepositCommand;
import br.com.walletcore.application.port.in.MoveMoneyUseCase.Destination;
import br.com.walletcore.application.port.in.MoveMoneyUseCase.TransferCommand;
import br.com.walletcore.application.port.in.MoveMoneyUseCase.WithdrawCommand;
import br.com.walletcore.application.port.in.OnboardCustomerUseCase;
import br.com.walletcore.application.port.in.ProvisionTenantUseCase;
import br.com.walletcore.application.port.in.QueryAccountUseCase;
import br.com.walletcore.application.port.out.TransactionRunner;
import br.com.walletcore.domain.account.Account;
import br.com.walletcore.domain.exception.BusinessRuleException;
import br.com.walletcore.domain.exception.ConflictException;
import br.com.walletcore.domain.exception.DomainException;
import br.com.walletcore.domain.exception.NotFoundException;
import br.com.walletcore.domain.shared.AccountId;
import br.com.walletcore.domain.shared.Money;
import br.com.walletcore.domain.shared.TenantId;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * End-to-end behaviour against a real PostgreSQL (Testcontainers, requires Docker):
 * concurrency control, idempotency, tenant isolation and ledger immutability. The application
 * connects as the non-superuser {@code wallet_app}, so Row Level Security is really enforced.
 */
@SpringBootTest
@ActiveProfiles("test")
class WalletCoreConcurrencyTest {

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

    @Autowired ProvisionTenantUseCase provisionTenant;
    @Autowired OnboardCustomerUseCase onboardCustomer;
    @Autowired MoveMoneyUseCase moveMoney;
    @Autowired QueryAccountUseCase query;
    @Autowired AuditLedgerUseCase audit;
    @Autowired TransactionRunner transactionRunner;
    @Autowired JdbcClient jdbc;

    // ------------------------------------------------------------------ helpers
    private TenantId newTenant() {
        return provisionTenant.provision(new ProvisionTenantUseCase.Command(
                "t-" + UUID.randomUUID().toString().substring(0, 8), "a-strong-client-secret-123", "Test Tenant",
                "12345678", "0001", 4, null)).id();
    }

    private static final String[] CPFS = {"52998224725", "11144477735", "39053344705", "16899535009", "71428793860"};
    private int cpfCounter = 0;

    private Account newAccount(TenantId tenant) {
        return onboardCustomer.onboard(new OnboardCustomerUseCase.Command(
                tenant, "Customer " + cpfCounter, CPFS[cpfCounter++ % CPFS.length], null)).account();
    }

    private void deposit(TenantId tenant, Account account, String amount) {
        moveMoney.deposit(new DepositCommand(tenant, account.id(), Money.ofDecimal(new BigDecimal(amount)), "seed",
                "seed-" + UUID.randomUUID()));
    }

    private Money balance(TenantId tenant, Account account) {
        return query.getAccount(tenant, account.id()).balance();
    }

    private static <T> List<Future<T>> runConcurrently(List<Callable<T>> tasks) throws InterruptedException {
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            return executor.invokeAll(tasks);
        }
    }

    // ------------------------------------------------------------------ tests
    @Test
    void concurrentDepositsAndWithdrawalsOnTheSameWalletStayExact() throws Exception {
        TenantId tenant = newTenant();
        Account wallet = newAccount(tenant);
        deposit(tenant, wallet, "1000.00");

        List<Callable<Void>> tasks = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            final int n = i;
            tasks.add(() -> {
                moveMoney.deposit(new DepositCommand(tenant, wallet.id(), Money.ofCents(1000), "in", "in-" + n));
                return null;
            });
            tasks.add(() -> {
                moveMoney.withdraw(new WithdrawCommand(tenant, wallet.id(), Money.ofCents(1000), "out", "out-" + n));
                return null;
            });
        }
        for (Future<Void> f : runConcurrently(tasks)) {
            f.get(); // any failure surfaces here
        }

        assertThat(balance(tenant, wallet)).isEqualTo(Money.ofCents(100_000));
        var report = audit.audit(tenant, wallet.id());
        assertThat(report.entryCount()).isEqualTo(201); // seed + 200 movements, gapless
        assertThat(report.consistent()).as(report.findings().toString()).isTrue();
    }

    @Test
    void concurrentWithdrawalsNeverOverdraw() throws Exception {
        TenantId tenant = newTenant();
        Account wallet = newAccount(tenant);
        deposit(tenant, wallet, "100.00");

        List<Callable<Boolean>> tasks = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            final int n = i;
            tasks.add(() -> {
                try {
                    moveMoney.withdraw(new WithdrawCommand(tenant, wallet.id(), Money.ofCents(1000), "", "w-" + n));
                    return true;
                } catch (BusinessRuleException e) {
                    assertThat(e.code()).isEqualTo("INSUFFICIENT_FUNDS");
                    return false;
                }
            });
        }
        long succeeded = 0;
        for (Future<Boolean> f : runConcurrently(tasks)) {
            if (f.get()) {
                succeeded++;
            }
        }

        assertThat(succeeded).isEqualTo(10); // exactly what the balance allows
        assertThat(balance(tenant, wallet)).isEqualTo(Money.ZERO);
        assertThat(audit.audit(tenant, wallet.id()).consistent()).isTrue();
    }

    @Test
    void oppositeTransfersDoNotDeadlockAndConserveMoney() throws Exception {
        TenantId tenant = newTenant();
        Account a = newAccount(tenant);
        Account b = newAccount(tenant);
        deposit(tenant, a, "500.00");
        deposit(tenant, b, "500.00");

        List<Callable<Void>> tasks = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            final int n = i;
            tasks.add(() -> {
                moveMoney.transfer(new TransferCommand(tenant, a.id(), new Destination.ById(b.id()),
                        Money.ofCents(100), "a>b", "ab-" + n));
                return null;
            });
            tasks.add(() -> {
                moveMoney.transfer(new TransferCommand(tenant, b.id(), new Destination.ById(a.id()),
                        Money.ofCents(100), "b>a", "ba-" + n));
                return null;
            });
        }
        for (Future<Void> f : runConcurrently(tasks)) {
            f.get();
        }

        assertThat(balance(tenant, a).cents() + balance(tenant, b).cents()).isEqualTo(100_000);
        assertThat(audit.audit(tenant, a.id()).consistent()).isTrue();
        assertThat(audit.audit(tenant, b.id()).consistent()).isTrue();
    }

    @Test
    void concurrentRetriesWithTheSameIdempotencyKeyPostOnce() throws Exception {
        TenantId tenant = newTenant();
        Account wallet = newAccount(tenant);

        List<Callable<Boolean>> tasks = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            tasks.add(() -> moveMoney.deposit(new DepositCommand(tenant, wallet.id(), Money.ofCents(5000), "once",
                    "same-key")).replayed());
        }
        long replays = 0;
        for (Future<Boolean> f : runConcurrently(tasks)) {
            if (f.get()) {
                replays++;
            }
        }

        assertThat(replays).isEqualTo(19);
        assertThat(balance(tenant, wallet)).isEqualTo(Money.ofCents(5000));
        assertThatThrownBy(() -> moveMoney.deposit(new DepositCommand(tenant, wallet.id(), Money.ofCents(9999), "x",
                "same-key"))).isInstanceOf(ConflictException.class);
    }

    @Test
    void tenantsCannotSeeEachOthersAccounts() {
        TenantId tenantA = newTenant();
        TenantId tenantB = newTenant();
        Account accountOfA = newAccount(tenantA);

        assertThat(query.getAccount(tenantA, accountOfA.id()).id()).isEqualTo(accountOfA.id());
        assertThatThrownBy(() -> query.getAccount(tenantB, accountOfA.id())).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> moveMoney.deposit(new DepositCommand(tenantB, accountOfA.id(), Money.ofCents(1), "",
                "leak-1"))).isInstanceOf(DomainException.class);
    }

    @Test
    void holderCheckMatchesTheRealHolderAndRespectsTenantIsolation() {
        TenantId tenantA = newTenant();
        TenantId tenantB = newTenant();
        Account accountOfA = newAccount(tenantA); // first account of the test instance: CPFS[0]
        var n = accountOfA.number();

        var valid = query.checkHolder(tenantA, n.branch(), n.number(), n.checkDigit(), CPFS[0]);
        assertThat(valid.result()).isEqualTo(QueryAccountUseCase.HolderCheck.Result.VALID);
        assertThat(valid.accountId()).isEqualTo(accountOfA.id());

        assertThat(query.checkHolder(tenantA, n.branch(), n.number(), n.checkDigit(), CPFS[1]).result())
                .isEqualTo(QueryAccountUseCase.HolderCheck.Result.TAX_ID_MISMATCH);
        // Same number and the right CPF, but asked as another tenant: RLS hides the row entirely.
        assertThat(query.checkHolder(tenantB, n.branch(), n.number(), n.checkDigit(), CPFS[0]).result())
                .isEqualTo(QueryAccountUseCase.HolderCheck.Result.ACCOUNT_NOT_FOUND);
    }

    @Test
    void concurrentReversalsOfOneWithdrawalCreditOnce() throws Exception {
        TenantId tenant = newTenant();
        Account wallet = newAccount(tenant);
        deposit(tenant, wallet, "100.00");
        var debit = moveMoney.withdraw(new WithdrawCommand(tenant, wallet.id(), Money.ofDecimal(new BigDecimal("40.00")),
                "pix", "pix-debit-" + UUID.randomUUID()));

        List<Callable<MoveMoneyUseCase.TransactionResult>> tasks = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            tasks.add(() -> moveMoney.reverseWithdrawal(new MoveMoneyUseCase.ReversalCommand(tenant, debit.id(), null)));
        }
        for (Future<MoveMoneyUseCase.TransactionResult> f : runConcurrently(tasks)) {
            f.get();
        }

        assertThat(balance(tenant, wallet)).isEqualTo(Money.ofDecimal(new BigDecimal("100.00")));
        // Another tenant cannot reverse it (RLS hides the withdrawal's legs).
        assertThatThrownBy(() -> moveMoney.reverseWithdrawal(new MoveMoneyUseCase.ReversalCommand(newTenant(),
                debit.id(), null))).isInstanceOf(NotFoundException.class);
    }

    @Test
    void ledgerIsAppendOnlyEvenForTheApplicationRole() {
        TenantId tenant = newTenant();
        Account wallet = newAccount(tenant);
        deposit(tenant, wallet, "10.00");

        assertThatThrownBy(() -> transactionRunner.inTransaction(tenant,
                () -> jdbc.sql("UPDATE ledger_entry SET amount_cents = 1").update()))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> transactionRunner.inTransaction(tenant,
                () -> jdbc.sql("DELETE FROM ledger_entry").update()))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void onboardingIssuesPaymentAccountNumbersAndRejectsDuplicates() {
        TenantId tenant = newTenant();
        Account first = newAccount(tenant);
        assertThat(first.number().type().bcbCode()).isEqualTo("TRAN");
        assertThat(first.number().branch()).isEqualTo("0001");
        assertThat(first.number().ispb()).isEqualTo("12345678");

        assertThatThrownBy(() -> onboardCustomer.onboard(new OnboardCustomerUseCase.Command(
                tenant, "Same person", CPFS[0], null))).isInstanceOf(ConflictException.class);
    }
}
