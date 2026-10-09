package br.com.walletcore.application.port.out;

import br.com.walletcore.domain.customer.Customer;
import br.com.walletcore.domain.customer.Email;
import br.com.walletcore.domain.customer.TaxId;
import br.com.walletcore.domain.shared.AccountId;
import br.com.walletcore.domain.shared.CustomerId;
import br.com.walletcore.domain.shared.TenantId;

import java.util.Optional;

public interface CustomerRepository {

    boolean existsByTaxId(TenantId tenantId, TaxId taxId);

    /** @throws br.com.walletcore.domain.exception.ConflictException when the tax id or external ref already exists */
    void insert(Customer customer);

    /**
     * Who the customer with this CPF/CNPJ is, and where codes may be sent to them (ADR-003 of wallet-app).
     * {@code accountId} is their payment account; {@code email} is null when none was registered.
     */
    record Contact(CustomerId customerId, String name, Email email, Customer.Status status, AccountId accountId) {
    }

    Optional<Contact> findContactByTaxId(TenantId tenantId, TaxId taxId);

    /** @return false when the customer does not exist for this tenant; a null email removes it */
    boolean updateEmail(TenantId tenantId, CustomerId customerId, Email email);
}
