package br.com.walletcore.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.walletcore.domain.exception.ValidationException;
import br.com.walletcore.domain.shared.Money;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class MoneyTest {

    @Test
    void convertsDecimalsToCents() {
        assertThat(Money.ofDecimal(new BigDecimal("10.5")).cents()).isEqualTo(1050);
        assertThat(Money.ofDecimal(new BigDecimal("0.01")).cents()).isEqualTo(1);
        assertThat(Money.ofDecimal(new BigDecimal("100")).cents()).isEqualTo(10_000);
        assertThat(Money.ofDecimal(new BigDecimal("1.500")).cents()).isEqualTo(150);
    }

    @Test
    void rejectsMoreThanTwoDecimalPlaces() {
        assertThatThrownBy(() -> Money.ofDecimal(new BigDecimal("1.001")))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("2 decimal places");
    }

    @Test
    void formatsAsDecimalWithTwoPlaces() {
        assertThat(Money.ofCents(1050).toDecimal().toPlainString()).isEqualTo("10.50");
        assertThat(Money.ofCents(5).toDecimal().toPlainString()).isEqualTo("0.05");
    }

    @Test
    void arithmeticIsExact() {
        assertThat(Money.ofCents(100).plus(Money.ofCents(250))).isEqualTo(Money.ofCents(350));
        assertThat(Money.ofCents(100).minus(Money.ofCents(250)).isNegative()).isTrue();
    }
}
