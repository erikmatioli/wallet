package br.com.walletcore.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.walletcore.domain.customer.TaxId;
import br.com.walletcore.domain.exception.ValidationException;
import org.junit.jupiter.api.Test;

class TaxIdTest {

    @Test
    void acceptsValidCpfWithOrWithoutMask() {
        assertThat(TaxId.parse("529.982.247-25").value()).isEqualTo("52998224725");
        assertThat(TaxId.parse("52998224725").type()).isEqualTo(TaxId.DocumentType.CPF);
    }

    @Test
    void rejectsInvalidCpf() {
        assertThatThrownBy(() -> TaxId.parse("529.982.247-24")).isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> TaxId.parse("111.111.111-11")).isInstanceOf(ValidationException.class);
    }

    @Test
    void acceptsNumericCnpj() {
        TaxId id = TaxId.parse("11.222.333/0001-81");
        assertThat(id.type()).isEqualTo(TaxId.DocumentType.CNPJ);
        assertThat(id.value()).isEqualTo("11222333000181");
    }

    @Test
    void acceptsAlphanumericCnpj() {
        TaxId id = TaxId.parse("12.abc.345/01de-35");
        assertThat(id.type()).isEqualTo(TaxId.DocumentType.CNPJ);
        assertThat(id.value()).isEqualTo("12ABC34501DE35");
    }

    @Test
    void rejectsInvalidCnpjAndGarbage() {
        assertThatThrownBy(() -> TaxId.parse("11.222.333/0001-82")).isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> TaxId.parse("not-a-document")).isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> TaxId.parse(null)).isInstanceOf(ValidationException.class);
    }

    @Test
    void masksForLogs() {
        assertThat(TaxId.parse("52998224725").masked()).isEqualTo("***4725");
    }
}
