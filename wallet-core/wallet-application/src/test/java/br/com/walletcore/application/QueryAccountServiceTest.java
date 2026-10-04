package br.com.walletcore.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import br.com.walletcore.application.port.in.QueryAccountUseCase.HolderCheck;
import br.com.walletcore.domain.exception.NotFoundException;
import br.com.walletcore.domain.exception.ValidationException;
import br.com.walletcore.domain.account.Account;
import br.com.walletcore.domain.customer.Customer;
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

    // ---------------------------------------------------------------- checkHolder (autorização de Pix)

    @Test
    void holderCheckIsValidForMatchingDocumentAndReturnsAccountId() {
        Account a = f.openCustomerAccount("52998224725");

        HolderCheck check = check(a, "529.982.247-25"); // com máscara, de propósito

        assertThat(check.result()).isEqualTo(HolderCheck.Result.VALID);
        assertThat(check.accountId()).isEqualTo(a.id());
    }

    @Test
    void holderCheckReportsMismatchWithoutAccountId() {
        Account a = f.openCustomerAccount("52998224725");

        HolderCheck check = check(a, "11144477735"); // CPF válido, de outra pessoa

        assertThat(check.result()).isEqualTo(HolderCheck.Result.TAX_ID_MISMATCH);
        assertThat(check.accountId()).isNull();
    }

    @Test
    void holderCheckTreatsMalformedDocumentAsMismatchNotAsError() {
        Account a = f.openCustomerAccount("52998224725");

        assertThat(check(a, "não-é-documento").result()).isEqualTo(HolderCheck.Result.TAX_ID_MISMATCH);
    }

    @Test
    void holderCheckReportsUnknownAccount() {
        HolderCheck check = f.query.checkHolder(f.tenant, "0001", "99999999", "0", "52998224725");

        assertThat(check.result()).isEqualTo(HolderCheck.Result.ACCOUNT_NOT_FOUND);
        assertThat(check.accountId()).isNull();
    }

    @Test
    void holderCheckReportsBlockedAndClosedAccountsBeforeComparingDocument() {
        Account blocked = f.withStatus(f.openCustomerAccount("52998224725"), Account.Status.BLOCKED);
        Account closed = f.withStatus(f.openCustomerAccount("11144477735"), Account.Status.CLOSED);

        // Documento errado de propósito: o estado da conta vem antes da comparação.
        assertThat(check(blocked, "11144477735").result()).isEqualTo(HolderCheck.Result.ACCOUNT_BLOCKED);
        assertThat(check(closed, "52998224725").result()).isEqualTo(HolderCheck.Result.ACCOUNT_CLOSED);
    }

    @Test
    void holderCheckReportsBlockedCustomerAsBlocked() {
        Account a = f.openCustomerAccount("52998224725");
        f.customerStatusByAccount.put(a.id(), Customer.Status.BLOCKED);

        assertThat(check(a, "52998224725").result()).isEqualTo(HolderCheck.Result.ACCOUNT_BLOCKED);
    }

    private HolderCheck check(Account a, String taxId) {
        return f.query.checkHolder(f.tenant, a.number().branch(), a.number().number(), a.number().checkDigit(), taxId);
    }
}
