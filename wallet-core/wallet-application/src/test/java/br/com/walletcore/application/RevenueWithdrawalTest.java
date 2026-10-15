package br.com.walletcore.application;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.walletcore.application.port.in.MoveMoneyUseCase.DepositCommand;
import br.com.walletcore.application.port.in.MoveMoneyUseCase.FeeCommand;
import br.com.walletcore.application.port.in.MoveMoneyUseCase.RevenueWithdrawalCommand;
import br.com.walletcore.application.port.in.MoveMoneyUseCase.TransactionResult;
import br.com.walletcore.application.port.in.TreasuryUseCase.RegisterCommand;
import br.com.walletcore.application.port.in.TreasuryUseCase.Revenue;
import br.com.walletcore.domain.account.Account;
import br.com.walletcore.domain.backing.BackingType;
import br.com.walletcore.domain.backing.RevenueWithdrawal.Destination;
import br.com.walletcore.domain.exception.DomainException;
import br.com.walletcore.domain.ledger.TransactionType;
import br.com.walletcore.domain.shared.BackingAccountId;
import br.com.walletcore.domain.shared.Money;
import java.time.Instant;
import java.time.YearMonth;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

/** ADR-015: the tenant's revenue taken out of the platform, from a backing account, and the month's totals. */
class RevenueWithdrawalTest {

    private static final Destination ITAU = new Destination("Itaú (341)", "0001", "12345-6");

    private final InMemoryFixture f = new InMemoryFixture();
    private int keys;

    private String key() {
        return "k-" + keys++;
    }

    private Account customerWithFee(long deposit, long fee) {
        Account a = f.openCustomerAccount();
        f.moveMoney.deposit(new DepositCommand(f.tenant, a.id(), Money.ofCents(deposit), "seed", key()));
        f.moveMoney.fee(new FeeCommand(f.tenant, a.id(), Money.ofCents(fee), "WITHDRAWAL", "Tarifa: saque", null,
                null, key()));
        return a;
    }

    private BackingAccountId reserves() {
        return f.treasury.register(new RegisterCommand(f.tenant, BackingType.RESERVES, "Reservas", null)).id();
    }

    private TransactionResult withdraw(BackingAccountId from, long cents, String document, String key) {
        return f.moveMoney.revenueWithdrawal(new RevenueWithdrawalCommand(f.tenant, from, Money.ofCents(cents), ITAU,
                document, "receita de setembro", f.operator, key));
    }

    private long revenue() {
        return f.accountRepository.findById(f.tenant, f.tenantRevenue()).orElseThrow().balance().cents();
    }

    private static String code(Executable action) {
        try {
            action.execute();
        } catch (DomainException e) {
            return e.code();
        } catch (Throwable e) {
            throw new AssertionError(e);
        }
        throw new AssertionError("expected a refusal");
    }

    @Test
    void theRevenueLeavesFromABackingAccountAndTheCustomersDoNotChange() {
        Account a = customerWithFee(10_000, 1_000);
        BackingAccountId reserves = reserves();

        TransactionResult out = withdraw(reserves, 600, "TED 000123", key());

        assertThat(out.type()).isEqualTo(TransactionType.REVENUE_WITHDRAWAL);
        assertThat(revenue()).isEqualTo(400);
        // The money left the platform from the Reservas, which had nothing registered: it goes negative, as a TED does.
        assertThat(f.expectedOf(reserves)).isEqualTo(-600);
        assertThat(f.accountRepository.findById(f.tenant, a.id()).orElseThrow().balance().cents()).isEqualTo(9_000);

        var line = f.treasury.statement(f.tenant, reserves, null, 10).entries().getFirst();
        assertThat(line.reference().document()).isEqualTo("TED 000123");
        Revenue r = f.treasury.revenue(f.tenant, null, 10);
        var withdrawal = r.withdrawals().get(out.id());
        assertThat(withdrawal.fromName()).isEqualTo("Reservas");
        assertThat(withdrawal.detail().destination().describe()).isEqualTo("Itaú (341), ag 0001, conta 12345-6");
        assertThat(withdrawal.detail().actor().operator()).isEqualTo("maria");
    }

    @Test
    void neverFromAregularizarNeverMoreThanTheRevenueAndAlwaysWithAReceipt() {
        customerWithFee(10_000, 1_000);
        BackingAccountId reserves = reserves();

        assertThat(code(() -> withdraw(f.backing(BackingType.UNASSIGNED), 100, "TED 1", key())))
                .isEqualTo("INVALID_TREASURY_SOURCE");
        assertThat(code(() -> withdraw(reserves, 1_001, "TED 1", key()))).isEqualTo("INSUFFICIENT_FUNDS");
        assertThat(code(() -> withdraw(reserves, 100, " ", key()))).isEqualTo("DOCUMENT_REQUIRED");
        assertThat(code(() -> f.moveMoney.revenueWithdrawal(new RevenueWithdrawalCommand(f.tenant, reserves,
                Money.ofCents(100), new Destination("Itaú", "", "1"), "TED 1", null, f.operator, key()))))
                .isEqualTo("INVALID_DESTINATION");
        assertThat(revenue()).isEqualTo(1_000);
    }

    @Test
    void aRepeatedRequestIsAnsweredWithTheFirstWithdrawal() {
        customerWithFee(10_000, 1_000);
        BackingAccountId reserves = reserves();

        TransactionResult first = withdraw(reserves, 300, "TED 9", "same");
        TransactionResult again = withdraw(reserves, 300, "TED 9", "same");

        assertThat(again.replayed()).isTrue();
        assertThat(again.id()).isEqualTo(first.id());
        assertThat(revenue()).isEqualTo(700);
        assertThat(code(() -> withdraw(reserves, 300, "TED 10", "same"))).isEqualTo("IDEMPOTENCY_KEY_REUSED");
    }

    @Test
    void theMonthShowsWhatTheFeesTookInAndWhatWasWithdrawn() {
        customerWithFee(10_000, 1_000);
        BackingAccountId reserves = reserves();
        withdraw(reserves, 600, "TED 1", key());

        Revenue september = f.treasury.revenue(f.tenant, null, 10);
        assertThat(september.month().month()).isEqualTo(YearMonth.of(2026, 9));
        assertThat(september.month().earned().cents()).isEqualTo(1_000);
        assertThat(september.month().withdrawn().cents()).isEqualTo(600);

        // October: 400 carried over from September is not October's revenue.
        f.clock.now = Instant.parse("2026-10-02T12:00:00Z");
        customerWithFee(10_000, 250);
        Revenue october = f.treasury.revenue(f.tenant, null, 10);
        assertThat(october.balance().cents()).isEqualTo(650);
        assertThat(october.month().earned().cents()).isEqualTo(250);
        assertThat(october.month().withdrawn().cents()).isZero();
    }
}
