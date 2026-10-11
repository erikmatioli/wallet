package br.com.walletcore.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.walletcore.application.port.in.MoveMoneyUseCase.DepositCommand;
import br.com.walletcore.application.port.in.MoveMoneyUseCase.PixCommand;
import br.com.walletcore.application.port.in.MoveMoneyUseCase.ReversalCommand;
import br.com.walletcore.application.port.in.MoveMoneyUseCase.TransactionResult;
import br.com.walletcore.application.port.in.MoveMoneyUseCase.TreasuryTransferCommand;
import br.com.walletcore.application.port.in.MoveMoneyUseCase.WithdrawCommand;
import br.com.walletcore.application.port.in.TreasuryUseCase;
import br.com.walletcore.application.port.in.TreasuryUseCase.Entry;
import br.com.walletcore.application.port.in.TreasuryUseCase.RegisterCommand;
import br.com.walletcore.application.port.in.TreasuryUseCase.Statement;
import br.com.walletcore.application.port.in.TreasuryUseCase.UncoveredDay;
import br.com.walletcore.domain.account.Account;
import br.com.walletcore.domain.backing.BackingAccount;
import br.com.walletcore.domain.backing.BackingType;
import br.com.walletcore.domain.backing.TreasuryReference;
import br.com.walletcore.domain.exception.BusinessRuleException;
import br.com.walletcore.domain.exception.DomainException;
import br.com.walletcore.domain.exception.NotFoundException;
import br.com.walletcore.domain.exception.ValidationException;
import br.com.walletcore.domain.ledger.TransactionType;
import br.com.walletcore.domain.pix.PixCounterparty;
import br.com.walletcore.domain.pix.PixDetail;
import br.com.walletcore.domain.shared.BackingAccountId;
import br.com.walletcore.domain.shared.Money;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** ADR-011: backing accounts, the routing of each rail, the treasury transfer and the treasury's reads. */
class TreasuryServiceTest {

    private static final ZoneId BRASILIA = ZoneId.of("America/Sao_Paulo");
    private final InMemoryFixture f = new InMemoryFixture();
    private final PixCounterparty maria = PixCounterparty.of("Maria Oliveira", "111.444.777-35", "99999999", "0001",
            "12345678", "TRAN");
    private int keys;

    private static Instant at(String localDateTime) {
        return LocalDateTime.parse(localDateTime).atZone(BRASILIA).toInstant();
    }

    private TransactionResult deposit(Account a, long cents) {
        return f.moveMoney.deposit(new DepositCommand(f.tenant, a.id(), Money.ofCents(cents), "depósito", "k" + keys++));
    }

    private TransactionResult withdraw(Account a, long cents) {
        return f.moveMoney.withdraw(new WithdrawCommand(f.tenant, a.id(), Money.ofCents(cents), "saque", "k" + keys++));
    }

    private TransactionResult pix(Account a, TransactionType type, long cents) {
        String e2e = String.format("E12345678202610061200abc%08d", keys);
        PixDetail detail = new PixDetail(type, e2e, null, null, maria, null, "pix");
        return f.moveMoney.postPix(new PixCommand(f.tenant, a.id(), Money.ofCents(cents), "pix", "k" + keys++, detail));
    }

    private TransactionResult move(BackingAccountId from, BackingAccountId to, long cents, String key) {
        return f.moveMoney.treasuryTransfer(new TreasuryTransferCommand(f.tenant, from, to, Money.ofCents(cents), "",
                new TreasuryReference("TED 000123", "resgate do CDB"), key));
    }

    private BackingAccountId register(BackingType type, String name) {
        return f.treasury.register(new RegisterCommand(f.tenant, type, name, "341 / 0001 / 12345-6")).id();
    }

    private long customersTotal() {
        return f.accounts.values().stream().filter(a -> a.kind() == Account.Kind.CUSTOMER)
                .mapToLong(a -> a.balance().cents()).sum();
    }

    private static String code(Throwable e) {
        return ((DomainException) e).code();
    }

    @Test
    void eachRailUsesItsBackingAccountAndTheyAddUpToTheCustomers() {
        Account a = f.openCustomerAccount();
        deposit(a, 10_000);                                   // money created
        pix(a, TransactionType.PIX_IN, 5_000);                // settled in the SPI
        pix(a, TransactionType.PIX_OUT, 2_000);
        TransactionResult cash = withdraw(a, 1_000);          // bank notes out of the ATM
        f.moveMoney.reverseWithdrawal(new ReversalCommand(f.tenant, cash.id(), ""));  // ...and back in

        assertThat(f.expectedOf(f.backing(BackingType.UNASSIGNED))).isEqualTo(10_000);
        assertThat(f.expectedOf(f.backing(BackingType.PIX_PI))).isEqualTo(3_000);
        assertThat(f.expectedOf(f.backing(BackingType.CASH))).isZero();
        assertThat(f.treasury.positions(f.tenant).stream().mapToLong(p -> p.expected().cents()).sum())
                .isEqualTo(customersTotal()).isEqualTo(13_000);
        assertThat(f.sumOfAllBalances()).isZero(); // still double entry
    }

    @Test
    void aCoverageMovesUncoveredMoneyToWhereItIsNowAndNeverExceedsIt() {
        Account a = f.openCustomerAccount();
        deposit(a, 22_376);
        BackingAccountId reserves = register(BackingType.RESERVES, "Reservas");
        BackingAccountId unassigned = f.backing(BackingType.UNASSIGNED);

        TransactionResult r = move(unassigned, reserves, 20_000, "c1");

        assertThat(r.type()).isEqualTo(TransactionType.TREASURY_TRANSFER);
        assertThat(r.description()).isEqualTo("Cobertura: A regularizar → Reservas");
        assertThat(f.expectedOf(unassigned)).isEqualTo(2_376);
        assertThat(f.expectedOf(reserves)).isEqualTo(20_000);
        assertThat(f.balanceOf(a)).isEqualTo(22_376); // no customer touched
        assertThat(f.treasuryReferences.get(r.id()).document()).isEqualTo("TED 000123");

        assertThatThrownBy(() -> move(unassigned, reserves, 2_377, "c2"))
                .isInstanceOf(BusinessRuleException.class)
                .satisfies(e -> assertThat(code(e)).isEqualTo("COVERAGE_EXCEEDS_UNCOVERED"));
        assertThat(move(unassigned, reserves, 20_000, "c1").replayed()).isTrue(); // same key: the original answer
        assertThat(f.expectedOf(unassigned)).isEqualTo(2_376);
    }

    @Test
    void aRebalancingMovesBetweenPlacesAndMayLeaveOneNegative() {
        Account a = f.openCustomerAccount();
        pix(a, TransactionType.PIX_IN, 5_000);
        BackingAccountId reserves = register(BackingType.RESERVES, "Reservas");
        BackingAccountId pi = f.backing(BackingType.PIX_PI);

        TransactionResult r = move(reserves, pi, 7_000, "r1"); // the treasury tops up the Conta PI from Reservas

        assertThat(r.description()).isEqualTo("Recomposição: Reservas → Conta PI");
        assertThat(f.expectedOf(pi)).isEqualTo(12_000);
        assertThat(f.expectedOf(reserves)).isEqualTo(-7_000); // flagged on the screen, not refused
    }

    @Test
    void nothingGoesIntoUnassignedNorToItselfNorToAnUnknownPlace() {
        BackingAccountId reserves = register(BackingType.RESERVES, "Reservas");
        BackingAccountId unassigned = f.backing(BackingType.UNASSIGNED);

        assertThatThrownBy(() -> move(reserves, unassigned, 100, "x1"))
                .satisfies(e -> assertThat(code(e)).isEqualTo("INVALID_TREASURY_DESTINATION"));
        assertThatThrownBy(() -> move(reserves, reserves, 100, "x2"))
                .satisfies(e -> assertThat(code(e)).isEqualTo("SAME_ACCOUNT"));
        assertThatThrownBy(() -> move(reserves, BackingAccountId.newId(), 100, "x3"))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void theDefaultsAreNotRegisteredTwice() {
        assertThatThrownBy(() -> register(BackingType.PIX_PI, "Outra PI")).isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> register(BackingType.INVESTMENT, " ")).isInstanceOf(ValidationException.class);
        BackingAccountId cdb = register(BackingType.INVESTMENT, "CDB Banco X");
        assertThat(f.backingRepository.findShardIds(f.tenant, cdb)).hasSize(1);
        assertThat(f.treasury.positions(f.tenant)).extracting(p -> p.account().name())
                .containsExactly("Conta PI", "Caixa", "A regularizar", "CDB Banco X");
    }

    @Test
    void theStatementMergesTheShardsWithTheRunningExpectedBalance() {
        Account a = f.openCustomerAccount();
        Instant t = at("2026-10-01T09:00:00");
        for (int i = 1; i <= 23; i++) {
            f.clock.now = t.plusSeconds(i * 60L * (i % 3 == 0 ? 0 : 1)); // some on the same instant
            deposit(a, i * 100L);
        }
        BackingAccountId unassigned = f.backing(BackingType.UNASSIGNED);
        BackingAccountId reserves = register(BackingType.RESERVES, "Reservas");
        f.clock.now = at("2026-10-01T18:00:00");
        move(unassigned, reserves, 5_000, "c1");

        List<Entry> all = new ArrayList<>();
        String cursor = null;
        do {
            Statement page = f.treasury.statement(f.tenant, unassigned, cursor, 7);
            assertThat(page.entries()).hasSizeLessThanOrEqualTo(7);
            all.addAll(page.entries());
            cursor = page.next();
        } while (cursor != null);

        assertThat(all).hasSize(24);
        Set<Object> seen = new HashSet<>();
        all.forEach(e -> assertThat(seen.add(e.entry().id())).isTrue());
        assertThat(all.getFirst().entry().type()).isEqualTo(TransactionType.TREASURY_TRANSFER);
        assertThat(all.getFirst().in()).isFalse();
        assertThat(all.getFirst().otherBackingAccount().name()).isEqualTo("Reservas");
        assertThat(all.getFirst().reference().note()).isEqualTo("resgate do CDB");
        assertThat(all.getFirst().expectedAfter().cents()).isEqualTo(f.expectedOf(unassigned));
        assertThat(all.get(1).customerName()).isEqualTo("Test Customer");
        for (int i = 0; i < all.size(); i++) {
            Entry e = all.get(i);
            long before = i + 1 < all.size() ? all.get(i + 1).expectedAfter().cents() : 0;
            long change = e.in() ? e.entry().amount().cents() : -e.entry().amount().cents();
            assertThat(e.expectedAfter().cents()).as("entry %d", i).isEqualTo(before + change);
            if (i > 0) {
                assertThat(e.entry().occurredAt()).isBeforeOrEqualTo(all.get(i - 1).entry().occurredAt());
            }
        }
        assertThatThrownBy(() -> f.treasury.statement(f.tenant, unassigned, "1.2", 7))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    void uncoveredMoneyDayByDay() {
        Account a = f.openCustomerAccount();
        BackingAccountId unassigned = f.backing(BackingType.UNASSIGNED);
        BackingAccountId reserves = register(BackingType.RESERVES, "Reservas");
        f.clock.now = at("2026-10-08T10:00:00");
        deposit(a, 10_000);
        f.clock.now = at("2026-10-08T23:59:00");
        deposit(a, 2_376);
        f.clock.now = at("2026-10-09T17:00:00");
        move(unassigned, reserves, 12_000, "c1");
        f.clock.now = at("2026-10-10T09:00:00");
        deposit(a, 500);
        f.clock.now = at("2026-10-12T09:00:00");

        List<UncoveredDay> days = f.treasury.uncoveredDaily(f.tenant, LocalDate.parse("2026-10-07"),
                LocalDate.parse("2026-10-11"));

        assertThat(days).extracting(d -> d.date().toString(), d -> d.created().cents(), d -> d.covered().cents(),
                d -> d.uncoveredAtEnd().cents()).containsExactly(
                org.assertj.core.groups.Tuple.tuple("2026-10-07", 0L, 0L, 0L),
                org.assertj.core.groups.Tuple.tuple("2026-10-08", 12_376L, 0L, 12_376L),
                org.assertj.core.groups.Tuple.tuple("2026-10-09", 0L, 12_000L, 376L),
                org.assertj.core.groups.Tuple.tuple("2026-10-10", 500L, 0L, 876L),
                org.assertj.core.groups.Tuple.tuple("2026-10-11", 0L, 0L, 876L));

        assertThat(f.treasury.dailyPositions(f.tenant, reserves, LocalDate.parse("2026-10-08"),
                LocalDate.parse("2026-10-09"))).extracting(d -> d.expected().cents()).containsExactly(0L, 12_000L);
        TreasuryUseCase.Position pos = f.treasury.positions(f.tenant).stream()
                .filter(p -> p.account().type() == BackingType.UNASSIGNED).findFirst().orElseThrow();
        assertThat(pos.expected().cents()).isEqualTo(876);
        assertThat(pos.expectedYesterday().cents()).isEqualTo(876); // the 11th
    }

    @Test
    void yesterdayIsUnknownWhileItMayStillHaveEntriesCommitting() {
        f.clock.now = at("2026-10-10T00:00:30");
        assertThat(f.treasury.positions(f.tenant)).allSatisfy(p -> assertThat(p.expectedYesterday()).isNull());
        f.clock.now = at("2026-10-10T00:01:00");
        assertThat(f.treasury.positions(f.tenant)).allSatisfy(p -> assertThat(p.expectedYesterday()).isNotNull());
    }

    @Test
    void registeredAccountsKeepTheirData() {
        BackingAccountId id = register(BackingType.CCME, "CCME no BC");
        BackingAccount stored = f.backingAccounts.get(id);
        assertThat(stored.type()).isEqualTo(BackingType.CCME);
        assertThat(stored.externalRef()).isEqualTo("341 / 0001 / 12345-6");
    }
}
