package br.com.walletcore.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.walletcore.application.port.in.AuditLedgerUseCase.AuditReport;
import br.com.walletcore.application.port.in.MoveMoneyUseCase.DepositCommand;
import br.com.walletcore.application.port.in.MoveMoneyUseCase.Destination;
import br.com.walletcore.application.port.in.MoveMoneyUseCase.TransactionResult;
import br.com.walletcore.application.port.in.MoveMoneyUseCase.TransferCommand;
import br.com.walletcore.application.port.in.MoveMoneyUseCase.WithdrawCommand;
import br.com.walletcore.domain.account.Account;
import br.com.walletcore.domain.exception.BusinessRuleException;
import br.com.walletcore.domain.exception.ConflictException;
import br.com.walletcore.domain.exception.DomainException;
import br.com.walletcore.domain.exception.NotFoundException;
import br.com.walletcore.domain.exception.ValidationException;
import br.com.walletcore.domain.ledger.LedgerEntry;
import br.com.walletcore.domain.ledger.TransactionType;
import br.com.walletcore.domain.shared.Money;
import java.util.List;
import org.junit.jupiter.api.Test;

class MoveMoneyServiceTest {

    private final InMemoryFixture f = new InMemoryFixture();

    private DepositCommand deposit(Account a, long cents, String key) {
        return new DepositCommand(f.tenant, a.id(), Money.ofCents(cents), "deposit", key);
    }

    @Test
    void depositCreditsCustomerAndKeepsLedgerBalanced() {
        Account a = f.openCustomerAccount();
        TransactionResult r = f.moveMoney.deposit(deposit(a, 10_000, "k1"));

        assertThat(r.replayed()).isFalse();
        assertThat(f.balanceOf(a)).isEqualTo(10_000);
        assertThat(f.entries).hasSize(2);
        assertThat(f.entries.stream().mapToLong(LedgerEntry::signedCents).sum()).isZero();
        assertThat(f.sumOfAllBalances()).isZero(); // money is neither created nor destroyed
        assertThat(f.events).hasSize(1);
    }

    @Test
    void withdrawalCannotOverdraw() {
        Account a = f.openCustomerAccount();
        f.moveMoney.deposit(deposit(a, 5_000, "k1"));

        assertThatThrownBy(() -> f.moveMoney.withdraw(
                new WithdrawCommand(f.tenant, a.id(), Money.ofCents(5_001), "", "k2")))
                .isInstanceOf(BusinessRuleException.class)
                .extracting(e -> ((DomainException) e).code()).isEqualTo("INSUFFICIENT_FUNDS");
        assertThat(f.balanceOf(a)).isEqualTo(5_000);

        f.moveMoney.withdraw(new WithdrawCommand(f.tenant, a.id(), Money.ofCents(5_000), "", "k3"));
        assertThat(f.balanceOf(a)).isZero();
    }

    @Test
    void sameIdempotencyKeyReplaysWithoutMovingMoneyTwice() {
        Account a = f.openCustomerAccount();
        TransactionResult first = f.moveMoney.deposit(deposit(a, 1_000, "same"));
        TransactionResult second = f.moveMoney.deposit(deposit(a, 1_000, "same"));

        assertThat(second.replayed()).isTrue();
        assertThat(second.id()).isEqualTo(first.id());
        assertThat(f.balanceOf(a)).isEqualTo(1_000);
        assertThat(f.entries).hasSize(2);
    }

    @Test
    void reusingKeyWithDifferentPayloadIsRejected() {
        Account a = f.openCustomerAccount();
        f.moveMoney.deposit(deposit(a, 1_000, "same"));
        assertThatThrownBy(() -> f.moveMoney.deposit(deposit(a, 2_000, "same")))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void idempotencyKeyIsRequired() {
        Account a = f.openCustomerAccount();
        assertThatThrownBy(() -> f.moveMoney.deposit(deposit(a, 1_000, " ")))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    void transferMovesMoneyByAccountNumberAndBothLedgersReplayCleanly() {
        Account a = f.openCustomerAccount();
        Account b = f.openCustomerAccount();
        f.moveMoney.deposit(deposit(a, 10_000, "d1"));

        Destination.ByNumber to = new Destination.ByNumber(b.number().branch(), b.number().number(),
                b.number().checkDigit());
        f.moveMoney.transfer(new TransferCommand(f.tenant, a.id(), to, Money.ofCents(2_500), "rent", "t1"));

        assertThat(f.balanceOf(a)).isEqualTo(7_500);
        assertThat(f.balanceOf(b)).isEqualTo(2_500);
        AuditReport auditA = f.audit.audit(f.tenant, a.id());
        AuditReport auditB = f.audit.audit(f.tenant, b.id());
        assertThat(auditA.consistent()).isTrue();
        assertThat(auditA.entryCount()).isEqualTo(2);
        assertThat(auditB.consistent()).isTrue();
        assertThat(auditB.replayedBalance()).isEqualTo(Money.ofCents(2_500));
    }

    @Test
    void everyLedgerEntryRecordsItsCounterpartyAccount() {
        Account a = f.openCustomerAccount();
        Account b = f.openCustomerAccount();
        f.moveMoney.deposit(deposit(a, 10_000, "d1"));
        f.moveMoney.transfer(new TransferCommand(f.tenant, a.id(), new Destination.ById(b.id()), Money.ofCents(1_000),
                "", "t1"));

        // Deposit: customer leg's counterparty is whichever settlement shard was picked - never
        // null, but which shard is intentionally not asserted here (see SettlementRouter).
        LedgerEntry depositLeg = f.entries.stream()
                .filter(e -> e.accountId().equals(a.id()) && e.type() == TransactionType.DEPOSIT)
                .findFirst().orElseThrow();
        assertThat(depositLeg.counterpartyAccountId()).isNotNull();

        // Transfer: each leg's counterparty is exactly the other account, both directions.
        LedgerEntry transferOut = f.entries.stream()
                .filter(e -> e.accountId().equals(a.id()) && e.type() == TransactionType.TRANSFER)
                .findFirst().orElseThrow();
        LedgerEntry transferIn = f.entries.stream()
                .filter(e -> e.accountId().equals(b.id()) && e.type() == TransactionType.TRANSFER)
                .findFirst().orElseThrow();
        assertThat(transferOut.counterpartyAccountId()).isEqualTo(b.id());
        assertThat(transferIn.counterpartyAccountId()).isEqualTo(a.id());
    }

    @Test
    void statementEnrichesTransferEntriesWithCounterpartyDisplayInfoButNotDeposits() {
        Account a = f.openCustomerAccount();
        Account b = f.openCustomerAccount();
        f.moveMoney.deposit(deposit(a, 10_000, "d1"));
        f.moveMoney.transfer(new TransferCommand(f.tenant, a.id(), new Destination.ById(b.id()), Money.ofCents(500),
                "", "t1"));

        var page = f.query.getStatement(f.tenant, a.id(), null, 10);
        var transferEntry = page.entries().stream().filter(se -> se.entry().type() == TransactionType.TRANSFER).findFirst().orElseThrow();
        var depositEntry = page.entries().stream().filter(se -> se.entry().type() == TransactionType.DEPOSIT).findFirst().orElseThrow();

        assertThat(transferEntry.counterpartyCustomerName()).isEqualTo("Test Customer");
        assertThat(transferEntry.counterpartyAccountFormatted()).isEqualTo(b.number().formatted());
        assertThat(depositEntry.counterpartyCustomerName()).isNull();
        assertThat(depositEntry.counterpartyAccountFormatted()).isNull();
    }

    @Test
    void transferValidatesDestination() {
        Account a = f.openCustomerAccount();
        f.moveMoney.deposit(deposit(a, 1_000, "d1"));

        assertThatThrownBy(() -> f.moveMoney.transfer(new TransferCommand(f.tenant, a.id(),
                new Destination.ById(a.id()), Money.ofCents(100), "", "t1")))
                .isInstanceOf(BusinessRuleException.class);
        assertThatThrownBy(() -> f.moveMoney.transfer(new TransferCommand(f.tenant, a.id(),
                new Destination.ByNumber("0001", "99999999", "0"), Money.ofCents(100), "", "t2")))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void internalSettlementAccountsCannotBeAddressedByClients() {
        Account a = f.openCustomerAccount();
        f.moveMoney.deposit(deposit(a, 1_000, "d1"));
        var settlementId = f.accountRepository.findSettlementAccountIds(f.tenant).get(0);

        // Uses transfer (not deposit/withdraw) on purpose: those two pick their settlement
        // counterparty via SettlementRouter's hash-based routing, which - with 3 shards in this
        // fixture - has a ~1/3 chance of coincidentally routing to this very settlementId. When
        // that happens, both legs of the transaction become the same account, and the domain
        // rejects it as SAME_ACCOUNT before ever reaching the account-kind check below, making
        // the test flaky. transfer's two legs are exactly source/destination as given - no
        // random routing - so the account-kind check below is exercised deterministically.
        assertThatThrownBy(() -> f.moveMoney.transfer(new TransferCommand(f.tenant, settlementId,
                new Destination.ById(a.id()), Money.ofCents(100), "", "x1")))
                .isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> f.query.getAccount(f.tenant, settlementId)).isInstanceOf(NotFoundException.class);
    }

    @Test
    void auditDetectsTamperedLedger() {
        Account a = f.openCustomerAccount();
        f.moveMoney.deposit(deposit(a, 1_000, "d1"));
        f.moveMoney.deposit(deposit(a, 500, "d2"));
        assertThat(f.audit.audit(f.tenant, a.id()).consistent()).isTrue();

        // someone rewrites history behind the application's back
        for (int i = 0; i < f.entries.size(); i++) {
            LedgerEntry e = f.entries.get(i);
            if (e.accountId().equals(a.id()) && e.sequence() == 1) {
                f.entries.set(i, new LedgerEntry(e.id(), e.tenantId(), e.transactionId(), e.accountId(), e.sequence(),
                        e.direction(), Money.ofCents(9_999), e.balanceAfter(), e.type(), e.description(),
                        e.counterpartyAccountId(), e.occurredAt()));
            }
        }
        AuditReport report = f.audit.audit(f.tenant, a.id());
        assertThat(report.consistent()).isFalse();
        assertThat(report.findings()).isNotEmpty();
    }

    @Test
    void statementIsPaginatedNewestFirst() {
        Account a = f.openCustomerAccount();
        for (int i = 1; i <= 5; i++) {
            f.moveMoney.deposit(deposit(a, i * 100L, "d" + i));
        }
        var page1 = f.query.getStatement(f.tenant, a.id(), null, 2);
        assertThat(page1.entries()).extracting(se -> se.entry().sequence()).containsExactly(5L, 4L);
        assertThat(page1.nextBefore()).isEqualTo(4L);

        var page3 = f.query.getStatement(f.tenant, a.id(), 2L, 2);
        assertThat(page3.entries()).extracting(se -> se.entry().sequence()).isEqualTo(List.of(1L));
        assertThat(page3.nextBefore()).isNull();
    }
}
