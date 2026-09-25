package br.com.walletcore.application.port.in;

import br.com.walletcore.domain.account.Account;
import br.com.walletcore.domain.customer.Customer;
import br.com.walletcore.domain.shared.TenantId;

/** Registers a customer and opens its payment account (conta de pagamento) in one atomic step. */
public interface OnboardCustomerUseCase {

    Result onboard(Command command);

    record Command(TenantId tenantId, String name, String taxId, String externalRef) {
    }

    record Result(Customer customer, Account account) {
    }
}
