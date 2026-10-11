package br.com.walletcore.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.walletcore.application.port.in.BalanceHistoryUseCase.ClosingBalance;
import br.com.walletcore.application.port.in.MoveMoneyUseCase.DepositCommand;
import br.com.walletcore.application.port.in.MoveMoneyUseCase.WithdrawCommand;
import br.com.walletcore.domain.account.Account;
import br.com.walletcore.domain.backing.BackingType;
import br.com.walletcore.domain.exception.BusinessRuleException;
import br.com.walletcore.domain.exception.DomainException;
import br.com.walletcore.domain.exception.NotFoundException;
import br.com.walletcore.domain.exception.ValidationException;
import br.com.walletcore.domain.ledger.LedgerEntry;
import br.com.walletcore.domain.shared.AccountId;
import br.com.walletcore.domain.shared.Money;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

/** ADR-012: the closing balance of a day, found by binary search over the account's sequence. */
class BalanceHistoryServiceTest {

    private static final ZoneId BRASILIA = ZoneId.of("America/Sao_Paulo");
    private final InMemoryFixture f = new InMemoryFixture();
    private int keys;

    /** Brasília wall-clock time, as the customer reads it. */
    private static Instant at(String localDateTime) {
        return java.time.LocalDateTime.parse(localDateTime).atZone(BRASILIA).toInstant();
    }

    private void depositAt(Account a, long cents, Instant when) {
        f.clock.now = when;
        f.moveMoney.deposit(new DepositCommand(f.tenant, a.id(), Money.ofCents(cents), "deposit", "k" + keys++));
    }

    private void withdrawAt(Account a, long cents, Instant when) {
        f.clock.now = when;
        f.moveMoney.withdraw(new WithdrawCommand(f.tenant, a.id(), Money.ofCents(cents), "", "k" + keys++));
    }

    /** The answer without any cleverness: every entry of the account, the last one before the cutoff. */
    private long naive(AccountId account, Instant cutoff) {
        return f.entries.stream()
                .filter(e -> e.accountId().equals(account) && e.occurredAt().isBefore(cutoff))
                .max(Comparator.comparingLong(LedgerEntry::sequence))
                .map(e -> e.balanceAfter().cents()).orElse(0L);
    }

    @Test
    void theDayEndsAtMidnightInBrasilia() {
        Account a = f.openCustomerAccount();
        depositAt(a, 10_000, at("2026-10-08T10:00:00"));
        depositAt(a, 500, at("2026-10-08T23:59:59.999"));   // still the 8th
        depositAt(a, 70, at("2026-10-09T00:00:00"));         // already the 9th
        f.clock.now = at("2026-10-12T09:00:00");

        assertThat(f.balanceHistory.closingBalance(f.tenant, a.id(), LocalDate.parse("2026-10-07")).balance().cents())
                .isZero();
        ClosingBalance eighth = f.balanceHistory.closingBalance(f.tenant, a.id(), LocalDate.parse("2026-10-08"));
        assertThat(eighth.balance().cents()).isEqualTo(10_500);
        assertThat(eighth.lastSequence()).isEqualTo(2);
        assertThat(eighth.cutoff()).isEqualTo(Instant.parse("2026-10-09T03:00:00Z"));
        assertThat(f.balanceHistory.closingBalance(f.tenant, a.id(), LocalDate.parse("2026-10-09")).balance().cents())
                .isEqualTo(10_570);
        // A day without entries keeps the previous day's balance.
        assertThat(f.balanceHistory.closingBalance(f.tenant, a.id(), LocalDate.parse("2026-10-11")).balance().cents())
                .isEqualTo(10_570);
    }

    @Test
    void binarySearchAgreesWithTheNaiveAnswerOnABusyLedger() {
        Account a = f.openCustomerAccount();
        Random random = new Random(42);
        Instant t = at("2026-01-01T00:00:00");
        long balance = 0;
        for (int i = 0; i < 1_500; i++) {
            // Bursts on the same instant, gaps of minutes to days, some right at midnight.
            t = t.plusSeconds(random.nextInt(4) == 0 ? 0 : random.nextInt(20) == 0 ? 86_400 : random.nextInt(3_600));
            if (balance > 1_000 && random.nextBoolean()) {
                long out = 1 + random.nextInt((int) Math.min(balance, 5_000));
                withdrawAt(a, out, t);
                balance -= out;
            } else {
                long in = 1 + random.nextInt(5_000);
                depositAt(a, in, t);
                balance += in;
            }
        }
        f.clock.now = t.plusSeconds(3 * 86_400);
        LocalDate last = t.atZone(BRASILIA).toLocalDate().plusDays(1);

        int maxReads = 0;
        for (LocalDate d = LocalDate.parse("2025-12-30"); !d.isAfter(last); d = d.plusDays(1)) {
            f.sequenceReads = 0;
            ClosingBalance c = f.balanceHistory.closingBalance(f.tenant, a.id(), d);
            maxReads = Math.max(maxReads, f.sequenceReads);
            assertThat(c.balance().cents()).as("closing balance of %s", d).isEqualTo(naive(a.id(), c.cutoff()));
        }
        // 1,500 entries: log2 rounds up to 11 lookups at most.
        assertThat(maxReads).isLessThanOrEqualTo(11);
    }

    @Test
    void dailyBalancesFillTheQuietDaysAndAgreeWithEachDayAlone() {
        Account a = f.openCustomerAccount();
        depositAt(a, 1_000, at("2026-10-01T09:00:00"));
        depositAt(a, 2_000, at("2026-10-03T22:00:00"));
        withdrawAt(a, 500, at("2026-10-03T23:30:00"));
        depositAt(a, 100, at("2026-10-06T08:00:00"));
        f.clock.now = at("2026-10-10T12:00:00");

        List<ClosingBalance> days = f.balanceHistory.dailyBalances(f.tenant, a.id(),
                LocalDate.parse("2026-09-30"), LocalDate.parse("2026-10-07"));

        assertThat(days).extracting(d -> d.balance().cents())
                .containsExactly(0L, 1_000L, 1_000L, 2_500L, 2_500L, 2_500L, 2_600L, 2_600L);
        for (ClosingBalance d : days) {
            assertThat(d).isEqualTo(f.balanceHistory.closingBalance(f.tenant, a.id(), d.date()));
        }
    }

    @Test
    void todayAndTheFutureAreNotClosedAndTheGraceCounts() {
        Account a = f.openCustomerAccount();
        f.clock.now = at("2026-10-10T00:00:30"); // 30 s after midnight: the 9th may still have entries committing
        assertThatThrownBy(() -> f.balanceHistory.closingBalance(f.tenant, a.id(), LocalDate.parse("2026-10-09")))
                .isInstanceOf(BusinessRuleException.class)
                .extracting(e -> ((DomainException) e).code()).isEqualTo("DAY_NOT_CLOSED");
        assertThatThrownBy(() -> f.balanceHistory.closingBalance(f.tenant, a.id(), LocalDate.parse("2026-10-10")))
                .isInstanceOf(BusinessRuleException.class);

        f.clock.now = at("2026-10-10T00:01:00"); // the grace is over
        assertThat(f.balanceHistory.closingBalance(f.tenant, a.id(), LocalDate.parse("2026-10-09")).balance().cents())
                .isZero();
    }

    @Test
    void periodsAreCheckedAndInternalAccountsAreNotExposed() {
        Account a = f.openCustomerAccount();
        f.clock.now = at("2027-03-01T12:00:00");
        assertThatThrownBy(() -> f.balanceHistory.dailyBalances(f.tenant, a.id(),
                LocalDate.parse("2026-10-10"), LocalDate.parse("2026-10-09")))
                .isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> f.balanceHistory.dailyBalances(f.tenant, a.id(),
                LocalDate.parse("2025-01-01"), LocalDate.parse("2026-01-02")))
                .isInstanceOf(ValidationException.class);
        assertThat(f.balanceHistory.dailyBalances(f.tenant, a.id(),
                LocalDate.parse("2026-01-01"), LocalDate.parse("2026-12-31"))).hasSize(365);

        AccountId shard = f.backingRepository.findShardIds(f.tenant, f.backing(BackingType.UNASSIGNED)).getFirst();
        assertThatThrownBy(() -> f.balanceHistory.closingBalance(f.tenant, shard, LocalDate.parse("2026-10-09")))
                .isInstanceOf(NotFoundException.class);
    }
}
