package br.com.walletcore.domain.account;

import br.com.walletcore.domain.shared.AccountId;
import br.com.walletcore.domain.shared.CustomerId;
import br.com.walletcore.domain.shared.Money;
import br.com.walletcore.domain.shared.TenantId;
import java.time.Instant;

/**
 * A ledger account. Three kinds exist:
 * <ul>
 *   <li>{@code CUSTOMER}: a payment account (conta de pagamento) that can never go negative;</li>
 *   <li>{@code BACKING}: an internal counter-account, one shard of a backing account (conta de lastro, ADR-011).
 *       It receives the opposite leg of every movement that enters or leaves the tenant, so that each transaction
 *       is a balanced double entry, in the place the money of that rail is kept. Several shards per backing
 *       account avoid a single hot row;</li>
 *   <li>{@code SETTLEMENT}: the single counter-account the backing accounts replaced. Its shards are kept, closed
 *       and at zero, only as history.</li>
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

    public enum Kind { CUSTOMER, SETTLEMENT, BACKING }

    public enum Status { ACTIVE, BLOCKED, CLOSED }

    public static Account openPayment(TenantId tenantId, CustomerId customerId, PaymentAccountNumber number, Instant now) {
        return new Account(AccountId.newId(), tenantId, Kind.CUSTOMER, customerId, number,
                Status.ACTIVE, false, Money.ZERO, 0L, now);
    }

    public static Account openSettlement(TenantId tenantId, Instant now) {
        return new Account(AccountId.newId(), tenantId, Kind.SETTLEMENT, null, null,
                Status.ACTIVE, true, Money.ZERO, 0L, now);
    }

    /** One shard of a backing account; the link to the backing account is kept by the repository. */
    public static Account openBackingShard(TenantId tenantId, Instant now) {
        return new Account(AccountId.newId(), tenantId, Kind.BACKING, null, null,
                Status.ACTIVE, true, Money.ZERO, 0L, now);
    }

    public boolean isActive() {
        return status == Status.ACTIVE;
    }
}
