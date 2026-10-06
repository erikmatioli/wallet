package br.com.walletcore.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.walletcore.domain.exception.ValidationException;
import br.com.walletcore.domain.ledger.EntryDirection;
import br.com.walletcore.domain.ledger.LedgerTransaction;
import br.com.walletcore.domain.ledger.TransactionType;
import br.com.walletcore.domain.pix.PixCounterparty;
import br.com.walletcore.domain.pix.PixDetail;
import br.com.walletcore.domain.shared.AccountId;
import br.com.walletcore.domain.shared.Money;
import br.com.walletcore.domain.shared.TenantId;
import br.com.walletcore.domain.shared.TransactionId;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class PixDetailTest {

    private static final String E2E = "E12345678202610061200abcDEF12345";
    private static final String RTR = "D99999999202610061210abcDEF12345";
    private final PixCounterparty counterparty = PixCounterparty.of("Maria", "11144477735", "99999999", null,
            "12345678", null);

    @ParameterizedTest
    @EnumSource(value = TransactionType.class, names = {"PIX_IN", "PIX_REFUND", "PIX_RETURN_IN"})
    void creditTypesCreditTheCustomer(TransactionType type) {
        assertThat(customerDirection(type)).isEqualTo(EntryDirection.CREDIT);
    }

    @ParameterizedTest
    @EnumSource(value = TransactionType.class, names = {"PIX_OUT", "PIX_RETURN_OUT"})
    void debitTypesDebitTheCustomer(TransactionType type) {
        assertThat(customerDirection(type)).isEqualTo(EntryDirection.DEBIT);
    }

    @Test
    void genericTypesAreNotPix() {
        assertThat(TransactionType.DEPOSIT.isPix()).isFalse();
        assertThatThrownBy(() -> LedgerTransaction.pix(TenantId.newId(), TransactionId.newId(),
                TransactionType.DEPOSIT, AccountId.newId(), AccountId.newId(), Money.ofCents(1), "", Instant.now()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void counterpartyKeepsOnlyTheMaskedDocument() {
        assertThat(counterparty.taxIdMasked()).isEqualTo("***7735");
        assertThatThrownBy(() -> PixCounterparty.of("Maria", "123", "99999999", null, "1", null))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    void returnsNeedReturnIdOriginalAndReason() {
        TransactionId original = TransactionId.newId();
        assertThat(new PixDetail(TransactionType.PIX_RETURN_IN, E2E, RTR, original, counterparty, "MD06", null))
                .isNotNull();
        assertThatThrownBy(() -> new PixDetail(TransactionType.PIX_RETURN_IN, E2E, null, original, counterparty,
                "MD06", null)).isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> new PixDetail(TransactionType.PIX_RETURN_IN, E2E, RTR, null, counterparty,
                "MD06", null)).isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> new PixDetail(TransactionType.PIX_RETURN_IN, E2E, RTR, original, counterparty,
                null, null)).isInstanceOf(ValidationException.class);
    }

    @Test
    void aPixStandsAloneAndNeedsAValidEndToEndId() {
        assertThatThrownBy(() -> new PixDetail(TransactionType.PIX_IN, E2E, null, TransactionId.newId(),
                counterparty, null, null)).isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> new PixDetail(TransactionType.PIX_IN, "E123", null, null, counterparty, null, null))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    void refundCopiesThePixAndPointsAtTheOriginal() {
        TransactionId original = TransactionId.newId();
        PixDetail out = new PixDetail(TransactionType.PIX_OUT, E2E, null, null, counterparty, null, "aluguel");
        PixDetail refund = out.refundOf(original, "AC03");
        assertThat(refund.type()).isEqualTo(TransactionType.PIX_REFUND);
        assertThat(refund.endToEndId()).isEqualTo(E2E);
        assertThat(refund.relatedTransactionId()).isEqualTo(original);
        assertThat(refund.counterparty()).isEqualTo(counterparty);
        assertThat(refund.reasonCode()).isEqualTo("AC03");
    }

    private static EntryDirection customerDirection(TransactionType type) {
        AccountId customer = AccountId.newId();
        return LedgerTransaction.pix(TenantId.newId(), TransactionId.newId(), type, customer, AccountId.newId(),
                        Money.ofCents(100), "", Instant.now())
                .legs().stream().filter(l -> l.accountId().equals(customer)).findFirst().orElseThrow().direction();
    }
}
