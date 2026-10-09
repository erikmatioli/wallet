package br.com.walletcore.application.service;

import br.com.walletcore.application.port.in.CustomerContactUseCase;
import br.com.walletcore.application.port.out.CustomerRepository;
import br.com.walletcore.application.port.out.CustomerRepository.Contact;
import br.com.walletcore.application.port.out.TransactionRunner;
import br.com.walletcore.domain.customer.Email;
import br.com.walletcore.domain.customer.TaxId;
import br.com.walletcore.domain.exception.NotFoundException;
import br.com.walletcore.domain.shared.CustomerId;
import br.com.walletcore.domain.shared.TenantId;

public final class CustomerContactService implements CustomerContactUseCase {

    private final TransactionRunner tx;
    private final CustomerRepository customers;

    public CustomerContactService(TransactionRunner tx, CustomerRepository customers) {
        this.tx = tx;
        this.customers = customers;
    }

    @Override
    public Contact findByTaxId(TenantId tenantId, String rawTaxId) {
        TaxId taxId = TaxId.parse(rawTaxId);
        return tx.readOnly(tenantId, () -> customers.findContactByTaxId(tenantId, taxId))
                .orElseThrow(CustomerContactService::notFound);
    }

    @Override
    public void changeEmail(TenantId tenantId, CustomerId customerId, String rawEmail) {
        Email email = Email.parseOrNull(rawEmail);
        if (!tx.inTransaction(tenantId, () -> customers.updateEmail(tenantId, customerId, email))) {
            throw notFound();
        }
    }

    private static NotFoundException notFound() {
        return new NotFoundException("CUSTOMER_NOT_FOUND", "customer not found");
    }
}
