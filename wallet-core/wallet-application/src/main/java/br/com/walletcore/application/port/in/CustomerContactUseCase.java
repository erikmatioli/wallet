package br.com.walletcore.application.port.in;

import br.com.walletcore.application.port.out.CustomerRepository.Contact;
import br.com.walletcore.domain.shared.CustomerId;
import br.com.walletcore.domain.shared.TenantId;

/**
 * The customer's contact as the operator registered it (ADR-003 of wallet-app). A product that talks to the
 * end customer - the app's backend - reads it to know where a code may be sent; the operator sets it in the
 * console. The email never goes into events: it is personal data, like the tax id.
 */
public interface CustomerContactUseCase {

    /** @throws br.com.walletcore.domain.exception.NotFoundException when this tenant has no such customer */
    Contact findByTaxId(TenantId tenantId, String rawTaxId);

    /**
     * Sets, changes or (with a blank {@code rawEmail}) removes the customer's email.
     *
     * @throws br.com.walletcore.domain.exception.NotFoundException when this tenant has no such customer
     */
    void changeEmail(TenantId tenantId, CustomerId customerId, String rawEmail);
}
