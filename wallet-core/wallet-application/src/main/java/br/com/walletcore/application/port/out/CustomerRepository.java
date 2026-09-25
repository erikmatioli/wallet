package br.com.walletcore.application.port.out;

import br.com.walletcore.domain.customer.Customer;
import br.com.walletcore.domain.customer.TaxId;
import br.com.walletcore.domain.shared.TenantId;

public interface CustomerRepository {

    boolean existsByTaxId(TenantId tenantId, TaxId taxId);

    /** @throws br.com.walletcore.domain.exception.ConflictException when the tax id or external ref already exists */
    void insert(Customer customer);
}
