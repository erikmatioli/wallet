package br.com.walletcore.domain.account;

import br.com.walletcore.domain.shared.AccountId;
import br.com.walletcore.domain.shared.CustomerId;
import br.com.walletcore.domain.shared.Money;
import br.com.walletcore.domain.shared.TenantId;
import java.time.Instant;

/**
 * A ledger account. Two kinds exist:
 * <ul>
 *   <li>{@code CUSTOMER}: a payment account (conta de pagamento) that can never go negative;</li>
 *   <li>{@code SETTLEMENT}: an internal counter-account that receives the opposite leg of every
 *       deposit/withdrawal so that each transaction is a balanced double entry. Several shards
 *       exist per tenant to avoid a single hot row.</li>
 * </ul>
 * {@code balance} and {@code version} are a projection of the ledger; {@code version} always
 * equals the sequence number of the last ledger entry of the account.
 */
public record Account(
        AccountId id,
        TenantId tenantId,
        Kind kind,
        CustomerId customerId,
        PaymentAccountNumber number,
        Status status,
        boolean allowNegativeBalance,
        Money balance,
        long version,
        Instant createdAt) {

    public enum Kind { CUSTOMER, SETTLEMENT }

    public enum Status { ACTIVE, BLOCKED, CLOSED }

    public static Account openPayment(TenantId tenantId, CustomerId customerId, PaymentAccountNumber number, Instant now) {
        return new Account(AccountId.newId(), tenantId, Kind.CUSTOMER, customerId, number,
                Status.ACTIVE, false, Money.ZERO, 0L, now);
    }

    public static Account openSettlement(TenantId tenantId, Instant now) {
        return new Account(AccountId.newId(), tenantId, Kind.SETTLEMENT, null, null,
                Status.ACTIVE, true, Money.ZERO, 0L, now);
    }

    public boolean isActive() {
        return status == Status.ACTIVE;
    }
}
