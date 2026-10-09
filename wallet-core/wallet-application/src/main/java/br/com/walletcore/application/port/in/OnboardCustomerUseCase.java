package br.com.walletcore.application.port.in;

import br.com.walletcore.domain.account.Account;
import br.com.walletcore.domain.customer.Customer;
import br.com.walletcore.domain.shared.TenantId;

/** Registers a customer and opens its payment account (conta de pagamento) in one atomic step. */
public interface OnboardCustomerUseCase {

    Result onboard(Command command);

    /** @param email optional: where the customer's products may send codes (ADR-003 of wallet-app) */
    record Command(TenantId tenantId, String name, String taxId, String externalRef, String email) {

        public Command(TenantId tenantId, String name, String taxId, String externalRef) {
            this(tenantId, name, taxId, externalRef, null);
        }
    }

    record Result(Customer customer, Account account) {
    }
}
