package br.com.walletcore.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import br.com.walletcore.domain.exception.NotFoundException;
import br.com.walletcore.domain.exception.ValidationException;
import br.com.walletcore.domain.account.Account;
import org.junit.jupiter.api.Test;

public class QueryAccountServiceTest {
    private final InMemoryFixture f = new InMemoryFixture();

    @Test
    void findsAccountByTaxId() {
        Account a = f.openCustomerAccount("52998224725");

        var found = f.query.getAccountByTaxId(f.tenant, "529.982.247-25"); // com máscara, de propósito
        assertThat(found.id()).isEqualTo(a.id());
    }

    @Test
    void taxIdNotFoundThrowsNotFound() {
        assertThatThrownBy(() -> f.query.getAccountByTaxId(f.tenant, "11144477735"))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void malformedTaxIdThrowsValidationNotNotFound() {
        assertThatThrownBy(() -> f.query.getAccountByTaxId(f.tenant, "não-é-documento"))
                .isInstanceOf(ValidationException.class); // não NotFoundException — repare na distinção
    }
}
