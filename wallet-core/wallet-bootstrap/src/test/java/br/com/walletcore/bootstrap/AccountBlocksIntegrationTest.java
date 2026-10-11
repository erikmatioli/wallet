package br.com.walletcore.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.walletcore.application.port.in.AccountControlUseCase;
import br.com.walletcore.application.port.in.AccountControlUseCase.BlockCommand;
import br.com.walletcore.application.port.in.AccountControlUseCase.CloseCommand;
import br.com.walletcore.application.port.out.AccountStatusEventRepository.CcsAccountEvent;
import br.com.walletcore.application.port.in.MoveMoneyUseCase;
import br.com.walletcore.application.port.in.MoveMoneyUseCase.DepositCommand;
import br.com.walletcore.application.port.in.MoveMoneyUseCase.JudicialDestination;
import br.com.walletcore.application.port.in.MoveMoneyUseCase.JudicialTransferCommand;
import br.com.walletcore.application.port.in.MoveMoneyUseCase.JudicialTransitOutCommand;
import br.com.walletcore.application.port.in.MoveMoneyUseCase.TransactionResult;
import br.com.walletcore.application.port.in.MoveMoneyUseCase.WithdrawCommand;
import br.com.walletcore.application.port.in.OnboardCustomerUseCase;
import br.com.walletcore.application.port.in.ProvisionTenantUseCase;
import br.com.walletcore.application.port.in.QueryAccountUseCase;
import br.com.walletcore.application.port.in.QueryAccountUseCase.HolderCheck;
import br.com.walletcore.application.port.in.TreasuryUseCase;
import br.com.walletcore.domain.account.Account;
import br.com.walletcore.domain.account.AccountBlock;
import br.com.walletcore.domain.account.AccountStatusEvent;
import br.com.walletcore.domain.account.Actor;
import br.com.walletcore.domain.backing.BackingType;
import br.com.walletcore.domain.exception.BusinessRuleException;
import br.com.walletcore.domain.shared.BackingAccountId;
import br.com.walletcore.domain.shared.Money;
import br.com.walletcore.domain.shared.TenantId;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * ADR-013 against a real PostgreSQL, as wallet_app through Row Level Security: the balance UPDATE reads the policy a
 * block wrote, a refused movement leaves nothing behind, the judicial transfer and its TEDs keep the double entry (and
 * two TEDs at once cannot exceed the order), and the CCS feed carries the opening and the closing with the full
 * document - to its own tenant only.
 */
@SpringBootTest
@ActiveProfiles("test")
class AccountBlocksIntegrationTest {

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

    private static final Set<String> SCOPES = Set.of("accounts:block", "accounts:judicial", "accounts:close");
    private static final Actor MARIA = new Actor("maria", "test");

    @Autowired ProvisionTenantUseCase provisionTenant;
    @Autowired OnboardCustomerUseCase onboardCustomer;
    @Autowired MoveMoneyUseCase moveMoney;
    @Autowired AccountControlUseCase control;
    @Autowired QueryAccountUseCase query;
    @Autowired TreasuryUseCase treasury;

    private TenantId newTenant() {
        return provisionTenant.provision(new ProvisionTenantUseCase.Command(
                "t-" + UUID.randomUUID().toString().substring(0, 8), "a-strong-client-secret-123", "Test Tenant",
                "12345678", "0001", 2, null)).id();
    }

    private Account newAccount(TenantId tenant, String taxId) {
        return onboardCustomer.onboard(new OnboardCustomerUseCase.Command(tenant, "Maria", taxId, null)).account();
    }

    private void deposit(TenantId tenant, Account a, long cents) {
        moveMoney.deposit(new DepositCommand(tenant, a.id(), Money.ofCents(cents), "d", "k-" + UUID.randomUUID()));
    }

    private AccountBlock block(TenantId tenant, Account a, AccountBlock.Type type) {
        boolean judicial = type == AccountBlock.Type.JUDICIAL;
        return control.block(new BlockCommand(tenant, a.id(), type,
                judicial ? AccountBlock.Reason.COURT_ORDER : AccountBlock.Reason.FRAUD_SUSPICION, null,
                judicial ? new AccountBlock.CourtOrder("0001234-56.2026.8.26.0100", "SISBAJUD 1") : null, MARIA,
                SCOPES));
    }

    private long balance(TenantId tenant, Account a) {
        return query.getAccount(tenant, a.id()).balance().cents();
    }

    private long expected(TenantId tenant, BackingType type) {
        return treasury.positions(tenant).stream().filter(p -> p.account().type() == type).findFirst().orElseThrow()
                .expected().cents();
    }

    private BackingAccountId backing(TenantId tenant, BackingType type) {
        return treasury.positions(tenant).stream().filter(p -> p.account().type() == type).findFirst().orElseThrow()
                .account().id();
    }

    @Test
    void aBlockedAccountRefusesWithoutLeavingAnythingBehindAndTheCourtTakesItsMoney() {
        TenantId tenant = newTenant();
        Account a = newAccount(tenant, "52998224725");
        deposit(tenant, a, 10_000);
        block(tenant, a, AccountBlock.Type.FRAUD);
        AccountBlock order = block(tenant, a, AccountBlock.Type.JUDICIAL);

        assertThatThrownBy(() -> moveMoney.withdraw(new WithdrawCommand(tenant, a.id(), Money.ofCents(1_000), "",
                "w-1"))).isInstanceOf(BusinessRuleException.class).hasMessageContaining("blocked");
        assertThat(expected(tenant, BackingType.CASH)).isZero(); // the cash leg rolled back with the refusal

        TransactionResult transfer = moveMoney.judicialTransfer(new JudicialTransferCommand(tenant, a.id(), order.id(),
                Money.ofCents(6_000), new JudicialDestination.ToTransit(), "ofício 123", MARIA, "court-1"));
        assertThat(balance(tenant, a)).isEqualTo(4_000);
        assertThat(control.pendingJudicialTransit(tenant)).singleElement()
                .satisfies(p -> assertThat(p.remaining().cents()).isEqualTo(6_000));

        moveMoney.judicialTransitOut(new JudicialTransitOutCommand(tenant, transfer.id(),
                backing(tenant, BackingType.PIX_PI), Money.ofCents(6_000), "TED 987", null, MARIA, "ted-1"));

        assertThat(control.pendingJudicialTransit(tenant)).isEmpty();
        // The customer still has 4.000; "a regularizar" still holds the 10.000 deposited; the TED left the PI.
        assertThat(expected(tenant, BackingType.UNASSIGNED)).isEqualTo(10_000);
        assertThat(expected(tenant, BackingType.PIX_PI)).isEqualTo(-6_000);
        var statement = query.getStatement(tenant, a.id(), null, 1);
        assertThat(statement.entries().getFirst().entry().description())
                .isEqualTo("Ordem judicial, processo 0001234-56.2026.8.26.0100");
    }

    @Test
    void twoTedsAtOnceCannotExceedTheJudicialTransfer() throws Exception {
        TenantId tenant = newTenant();
        Account a = newAccount(tenant, "52998224725");
        deposit(tenant, a, 10_000);
        AccountBlock order = block(tenant, a, AccountBlock.Type.JUDICIAL);
        TransactionResult transfer = moveMoney.judicialTransfer(new JudicialTransferCommand(tenant, a.id(), order.id(),
                Money.ofCents(10_000), new JudicialDestination.ToTransit(), null, MARIA, "court-1"));
        BackingAccountId pi = backing(tenant, BackingType.PIX_PI);

        List<Callable<Boolean>> tasks = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            String key = "ted-" + i;
            tasks.add(() -> {
                try {
                    moveMoney.judicialTransitOut(new JudicialTransitOutCommand(tenant, transfer.id(), pi,
                            Money.ofCents(7_000), "TED " + key, null, MARIA, key));
                    return true;
                } catch (BusinessRuleException e) {
                    assertThat(e.code()).isIn("TRANSIT_OUT_EXCEEDS_TRANSFER", "INSUFFICIENT_FUNDS");
                    return false;
                }
            });
        }
        ExecutorService pool = Executors.newFixedThreadPool(2);
        long passed = 0;
        try {
            for (Future<Boolean> f : pool.invokeAll(tasks)) {
                try {
                    passed += f.get() ? 1 : 0;
                } catch (ExecutionException e) {
                    throw new AssertionError(e.getCause());
                }
            }
        } finally {
            pool.shutdown();
        }
        assertThat(passed).isEqualTo(1);
        assertThat(control.pendingJudicialTransit(tenant)).singleElement()
                .satisfies(p -> assertThat(p.remaining().cents()).isEqualTo(3_000));
    }

    @Test
    void theCcsFeedCarriesTheOpeningAndTheClosingToItsOwnTenantOnly() {
        TenantId tenant = newTenant();
        TenantId other = newTenant();
        Account a = newAccount(tenant, "52998224725");
        newAccount(other, "11144477735");
        block(tenant, a, AccountBlock.Type.FRAUD);

        control.close(new CloseCommand(tenant, a.id(), AccountStatusEvent.CloseReason.CUSTOMER_REQUEST, null, MARIA));

        List<CcsAccountEvent> events = control.ccsEvents(tenant, 0, 500).events();
        assertThat(events).extracting(CcsAccountEvent::kind)
                .containsExactly(CcsAccountEvent.Kind.OPENED, CcsAccountEvent.Kind.CLOSED);
        assertThat(events.getFirst().seq()).isLessThan(events.getLast().seq());
        assertThat(events.getLast().taxId()).isEqualTo("52998224725");
        assertThat(events.getLast().number()).isEqualTo(a.number().number());
        assertThat(control.ccsEvents(other, 0, 500).events()).singleElement()
                .satisfies(e -> assertThat(e.taxId()).isEqualTo("11144477735"));

        Account closed = query.getAccount(tenant, a.id());
        assertThat(closed.status()).isEqualTo(Account.Status.CLOSED);
        assertThat(closed.closedAt()).isNotNull();
        assertThat(control.blocks(tenant, a.id(), true)).isEmpty();
        HolderCheck check = query.checkHolder(tenant, a.number().branch(), a.number().number(),
                a.number().checkDigit(), "52998224725");
        assertThat(check.result()).isEqualTo(HolderCheck.Result.ACCOUNT_CLOSED);
        assertThat(control.timeline(tenant, a.id())).extracting(i -> i.kind()).containsExactly(
                AccountControlUseCase.TimelineKind.CLOSED, AccountControlUseCase.TimelineKind.RELEASED,
                AccountControlUseCase.TimelineKind.BLOCKED, AccountControlUseCase.TimelineKind.OPENED);
    }

    @Test
    void anAdministrativeBlockIsReadByTheHolderCheckAndTheListing() {
        TenantId tenant = newTenant();
        Account a = newAccount(tenant, "52998224725");
        block(tenant, a, AccountBlock.Type.ADMINISTRATIVE);

        assertThat(query.checkHolder(tenant, a.number().branch(), a.number().number(), a.number().checkDigit(),
                "52998224725").result()).isEqualTo(HolderCheck.Result.ACCOUNT_BLOCKED);
        Account stored = query.getAccount(tenant, a.id());
        assertThat(stored.policy().canReceive()).isFalse();
        assertThat(stored.version()).isZero(); // a block moves no money: the ledger sequence stays
    }
}
