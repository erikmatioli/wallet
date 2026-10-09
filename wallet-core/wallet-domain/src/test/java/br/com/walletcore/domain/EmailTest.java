package br.com.walletcore.domain;

import br.com.walletcore.domain.customer.Email;
import br.com.walletcore.domain.exception.ValidationException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EmailTest {

    @Test
    void isTrimmedLowerCasedAndNeverPrintedInFull() {
        Email email = Email.parseOrNull("  Maria.Silva@Example.COM ");
        assertThat(email.value()).isEqualTo("maria.silva@example.com");
        assertThat(email.masked()).isEqualTo("m***@example.com");
        assertThat(email.toString()).isEqualTo("m***@example.com");
    }

    @Test
    void blankMeansNoEmail() {
        assertThat(Email.parseOrNull(null)).isNull();
        assertThat(Email.parseOrNull("   ")).isNull();
    }

    @Test
    void refusesWhatIsNotAnAddress() {
        for (String bad : new String[] {"maria", "maria@", "@example.com", "maria @example.com", "a@b"}) {
            assertThatThrownBy(() -> Email.parseOrNull(bad)).isInstanceOf(ValidationException.class);
        }
    }
}
