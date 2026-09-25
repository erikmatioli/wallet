package br.com.walletcore.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.walletcore.domain.account.AccountType;
import br.com.walletcore.domain.account.PaymentAccountNumber;
import br.com.walletcore.domain.exception.BusinessRuleException;
import br.com.walletcore.domain.exception.ValidationException;
import org.junit.jupiter.api.Test;

class PaymentAccountNumberTest {

    @Test
    void generatesZeroPaddedNumberWithCheckDigit() {
        PaymentAccountNumber n = PaymentAccountNumber.generate("12345678", "0001", 1, AccountType.PAYMENT);
        assertThat(n.number()).isEqualTo("00000001");
        assertThat(n.checkDigit()).isEqualTo("7");
        assertThat(n.formatted()).isEqualTo("0001-00000001-7");
        assertThat(n.type().bcbCode()).isEqualTo("TRAN");
    }

    @Test
    void checkDigitCanBeZero() {
        assertThat(PaymentAccountNumber.computeCheckDigit("000100100001")).isEqualTo("0");
    }

    @Test
    void validatesShape() {
        assertThatThrownBy(() -> new PaymentAccountNumber("123", "0001", "1", "1", AccountType.PAYMENT))
                .isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> new PaymentAccountNumber("12345678", "1", "1", "1", AccountType.PAYMENT))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    void failsWhenSequenceIsExhausted() {
        assertThatThrownBy(() -> PaymentAccountNumber.generate("12345678", "0001", 100_000_000L, AccountType.PAYMENT))
                .isInstanceOf(BusinessRuleException.class);
    }
}
