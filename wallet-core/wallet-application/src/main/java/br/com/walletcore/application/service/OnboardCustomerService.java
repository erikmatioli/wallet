package br.com.walletcore.application.service;

import br.com.walletcore.application.port.in.OnboardCustomerUseCase;
import br.com.walletcore.application.port.out.AccountRepository;
import br.com.walletcore.application.port.out.CustomerRepository;
import br.com.walletcore.application.port.out.MetricsRecorder;
import br.com.walletcore.application.port.out.OutboxRepository;
import br.com.walletcore.application.port.out.TenantRepository;
import br.com.walletcore.application.port.out.TransactionRunner;
import br.com.walletcore.domain.account.Account;
import br.com.walletcore.domain.account.AccountType;
import br.com.walletcore.domain.account.PaymentAccountNumber;
import br.com.walletcore.domain.customer.Customer;
import br.com.walletcore.domain.customer.TaxId;
import br.com.walletcore.domain.event.CustomerOnboarded;
import br.com.walletcore.domain.exception.ConflictException;
import br.com.walletcore.domain.exception.NotFoundException;
import br.com.walletcore.domain.shared.UuidV7;
import br.com.walletcore.domain.tenant.Tenant;
import java.time.Clock;
import java.time.Instant;
import java.util.List;

public final class OnboardCustomerService implements OnboardCustomerUseCase {

    private final TransactionRunner tx;
    private final TenantRepository tenants;
    private final CustomerRepository customers;
    private final AccountRepository accounts;
    private final OutboxRepository outbox;
    private final MetricsRecorder metrics;
    private final Clock clock;

    public OnboardCustomerService(TransactionRunner tx, TenantRepository tenants, CustomerRepository customers,
                                  AccountRepository accounts, OutboxRepository outbox, MetricsRecorder metrics,
                                  Clock clock) {
        this.tx = tx;
        this.tenants = tenants;
        this.customers = customers;
        this.accounts = accounts;
        this.outbox = outbox;
        this.metrics = metrics;
        this.clock = clock;
    }

    @Override
    public Result onboard(Command c) {
        TaxId taxId = TaxId.parse(c.taxId());
        Instant now = clock.instant();
        Customer customer = Customer.onboard(c.tenantId(), c.name(), taxId, c.externalRef(), now);

        return tx.inTransaction(c.tenantId(), () -> {
            Tenant tenant = tenants.findById(c.tenantId())
                    .filter(Tenant::isActive)
                    .orElseThrow(() -> new NotFoundException("TENANT_NOT_FOUND", "tenant not found"));
            if (customers.existsByTaxId(c.tenantId(), taxId)) {
                throw new ConflictException("CUSTOMER_ALREADY_EXISTS", "a customer with this taxId already exists");
            }
            PaymentAccountNumber number = PaymentAccountNumber.generate(
                    tenant.ispb(), tenant.branch(), accounts.nextAccountSequence(), AccountType.PAYMENT);
            Account account = Account.openPayment(c.tenantId(), customer.id(), number, now);

            customers.insert(customer);
            accounts.insert(account);
            outbox.enqueue(List.of(new CustomerOnboarded(
                    UuidV7.next(), c.tenantId().value(), customer.id().value(), account.id().value(),
                    taxId.type().name(), number.branch(), number.number(), number.checkDigit(),
                    number.type().bcbCode(), now)));
            metrics.customerOnboarded(c.tenantId());
            return new Result(customer, account);
        });
    }
}
