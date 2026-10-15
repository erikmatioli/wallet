package br.com.walletcore.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.walletcore.application.port.in.AccountControlUseCase;
import br.com.walletcore.application.port.in.AccountControlUseCase.BlockCommand;
import br.com.walletcore.application.port.in.MoveMoneyUseCase;
import br.com.walletcore.application.port.in.MoveMoneyUseCase.DepositCommand;
import br.com.walletcore.application.port.in.MoveMoneyUseCase.FeeCommand;
import br.com.walletcore.application.port.in.MoveMoneyUseCase.FeeRefundCommand;
import br.com.walletcore.application.port.in.MoveMoneyUseCase.PixCommand;
import br.com.walletcore.application.port.in.MoveMoneyUseCase.TransactionResult;
import br.com.walletcore.application.port.in.MoveMoneyUseCase.WithdrawCommand;
import br.com.walletcore.application.port.in.OnboardCustomerUseCase;
import br.com.walletcore.application.port.in.ProvisionTenantUseCase;
import br.com.walletcore.application.port.in.QueryAccountUseCase;
import br.com.walletcore.application.port.in.TreasuryUseCase;
import br.com.walletcore.domain.account.Account;
import br.com.walletcore.domain.account.AccountBlock;
import br.com.walletcore.domain.account.Actor;
import br.com.walletcore.domain.exception.BusinessRuleException;
import br.com.walletcore.domain.ledger.TransactionType;
import br.com.walletcore.domain.pix.PixCounterparty;
import br.com.walletcore.domain.pix.PixDetail;
import br.com.walletcore.domain.shared.Money;
import br.com.walletcore.domain.shared.TenantId;
import java.util.Set;
import java.util.UUID;
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
 * ADR-014 against a real PostgreSQL, as wallet_app through Row Level Security: a fee goes to the tenant's revenue
 * account and its refund comes back from it, the fee block lets the fee out and nothing else, and a Pix keeps the
 * type of its counterparty's document.
 */
@SpringBootTest
@ActiveProfiles("test")
class FeesIntegrationTest {

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
    @Autowired AccountControlUseCase control;
    @Autowired QueryAccountUseCase query;
    @Autowired TreasuryUseCase treasury;

    private TenantId newTenant() {
        return provisionTenant.provision(new ProvisionTenantUseCase.Command(
                "t-" + UUID.randomUUID().toString().substring(0, 8), "a-strong-client-secret-123", "Test Tenant",
                "12345678", "0001", 2, null)).id();
    }

    private Account newAccount(TenantId tenant) {
        return onboardCustomer.onboard(new OnboardCustomerUseCase.Command(tenant, "Maria", "52998224725", null))
                .account();
    }

    private TransactionResult fee(TenantId tenant, Account a, long cents, String key) {
        return moveMoney.fee(new FeeCommand(tenant, a.id(), Money.ofCents(cents), "WITHDRAWAL", "Tarifa: saque", null,
                null, key));
    }

    @Test
    void aFeeGoesToTheRevenueAccountAndItsRefundComesBack() {
        TenantId tenant = newTenant();
        Account a = newAccount(tenant);
        moveMoney.deposit(new DepositCommand(tenant, a.id(), Money.ofCents(10_000), "d", "d-1"));

        TransactionResult charged = fee(tenant, a, 150, "fee-1");
        assertThat(treasury.revenue(tenant, null, 10).balance().cents()).isEqualTo(150);

        moveMoney.feeRefund(new FeeRefundCommand(tenant, charged.id(), "Estorno de tarifa: saque"));
        moveMoney.feeRefund(new FeeRefundCommand(tenant, charged.id(), null)); // replayed, not twice

        var revenue = treasury.revenue(tenant, null, 10);
        assertThat(revenue.balance().cents()).isZero();
        assertThat(revenue.entries()).extracting(e -> e.type())
                .containsExactly(TransactionType.FEE_REFUND, TransactionType.FEE);
        assertThat(query.getAccount(tenant, a.id()).balance().cents()).isEqualTo(10_000);
    }

    @Test
    void theFeeBlockLetsTheFeeOutAndNothingElse() {
        TenantId tenant = newTenant();
        Account a = newAccount(tenant);
        moveMoney.deposit(new DepositCommand(tenant, a.id(), Money.ofCents(1_000), "d", "d-1"));
        control.block(new BlockCommand(tenant, a.id(), AccountBlock.Type.FEE_PENDING, AccountBlock.Reason.UNPAID_FEES,
                null, null, new Actor("wallet-fees", "test"), Set.of("fees:write")));

        assertThatThrownBy(() -> moveMoney.withdraw(new WithdrawCommand(tenant, a.id(), Money.ofCents(100), "", "w-1")))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("blocked");
        fee(tenant, a, 150, "fee-1");
        assertThatThrownBy(() -> fee(tenant, a, 2_000, "fee-2")).isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("insufficient");

        assertThat(query.getAccount(tenant, a.id()).balance().cents()).isEqualTo(850);
        assertThat(treasury.revenue(tenant, null, 10).balance().cents()).isEqualTo(150);
    }

    @Test
    void aPixKeepsOnlyTheTypeOfItsCounterpartysDocument() {
        TenantId tenant = newTenant();
        Account a = newAccount(tenant);
        PixDetail detail = new PixDetail(TransactionType.PIX_IN, "E99999999202610151200abcDEF12345", null, null,
                PixCounterparty.of("Empresa", "11.222.333/0001-81", "99999999", "0001", "123456", "TRAN"), null, null);

        moveMoney.postPix(new PixCommand(tenant, a.id(), Money.ofCents(500), "pix", "p-1", detail));

        var entry = query.getStatement(tenant, a.id(), null, 1).entries().getFirst();
        assertThat(entry.pix().counterparty().taxIdType()).isEqualTo("CNPJ");
        assertThat(entry.pix().counterparty().taxIdMasked()).isEqualTo("***0181");
    }
}
