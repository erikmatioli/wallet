package br.com.walletcore.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.walletcore.domain.exception.BusinessRuleException;
import br.com.walletcore.domain.exception.ValidationException;
import br.com.walletcore.domain.ledger.EntryDirection;
import br.com.walletcore.domain.ledger.LedgerTransaction;
import br.com.walletcore.domain.ledger.Leg;
import br.com.walletcore.domain.shared.AccountId;
import br.com.walletcore.domain.shared.Money;
import br.com.walletcore.domain.shared.TenantId;
import br.com.walletcore.domain.shared.TransactionId;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class LedgerTransactionTest {

    private final TenantId tenant = TenantId.newId();
    private final Instant now = Instant.parse("2026-09-21T12:00:00Z");

    @Test
    void depositDebitsSettlementAndCreditsCustomer() {
        AccountId customer = AccountId.newId();
        AccountId settlement = AccountId.newId();
        LedgerTransaction tx = LedgerTransaction.deposit(tenant, TransactionId.newId(), customer, settlement,
                Money.ofCents(1000), "  pix in  ", now);

        assertThat(tx.description()).isEqualTo("pix in");
        assertThat(tx.legs()).extracting(Leg::signedCents).containsExactlyInAnyOrder(-1000L, 1000L);
        assertThat(tx.legs().stream().filter(l -> l.accountId().equals(customer)).findFirst().orElseThrow().direction())
                .isEqualTo(EntryDirection.CREDIT);
    }

    @Test
    void withdrawalDebitsCustomer() {
        AccountId customer = AccountId.newId();
        LedgerTransaction tx = LedgerTransaction.withdrawal(tenant, TransactionId.newId(), customer,
                AccountId.newId(), Money.ofCents(500), "", now);
        assertThat(tx.legs().stream().filter(l -> l.accountId().equals(customer)).findFirst().orElseThrow().direction())
                .isEqualTo(EntryDirection.DEBIT);
    }

    @Test
    void rejectsTransferToSameAccount() {
        AccountId a = AccountId.newId();
        assertThatThrownBy(() -> LedgerTransaction.transfer(tenant, TransactionId.newId(), a, a, Money.ofCents(100), "", now))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("different accounts");
    }

    @Test
    void rejectsNonPositiveAmounts() {
        assertThatThrownBy(() -> LedgerTransaction.transfer(tenant, TransactionId.newId(), AccountId.newId(),
                AccountId.newId(), Money.ZERO, "", now)).isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> LedgerTransaction.transfer(tenant, TransactionId.newId(), AccountId.newId(),
                AccountId.newId(), Money.ofCents(-1), "", now)).isInstanceOf(ValidationException.class);
    }

    @Test
    void rejectsUnbalancedLegs() {
        AccountId a = AccountId.newId();
        AccountId b = AccountId.newId();
        assertThatThrownBy(() -> new LedgerTransaction(TransactionId.newId(), tenant,
                br.com.walletcore.domain.ledger.TransactionType.TRANSFER, Money.ofCents(100), "",
                List.of(new Leg(a, EntryDirection.DEBIT, Money.ofCents(100)),
                        new Leg(b, EntryDirection.CREDIT, Money.ofCents(99))), now))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("debits must equal total credits");
    }

    @Test
    void lockOrderIsIndependentOfTransferDirection() {
        AccountId a = AccountId.newId();
        AccountId b = AccountId.newId();
        List<AccountId> forward = LedgerTransaction.transfer(tenant, TransactionId.newId(), a, b, Money.ofCents(1), "", now)
                .legsInLockOrder().stream().map(Leg::accountId).toList();
        List<AccountId> backward = LedgerTransaction.transfer(tenant, TransactionId.newId(), b, a, Money.ofCents(1), "", now)
                .legsInLockOrder().stream().map(Leg::accountId).toList();
        assertThat(forward).isEqualTo(backward);
    }

    @Test
    void rejectsTooLongDescription() {
        assertThatThrownBy(() -> LedgerTransaction.transfer(tenant, TransactionId.newId(), AccountId.newId(),
                AccountId.newId(), Money.ofCents(1), "x".repeat(141), now)).isInstanceOf(ValidationException.class);
    }
}
