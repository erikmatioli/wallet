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
 *   <li>{@code JUDICIAL_TRANSIT}: "depósitos judiciais a transferir" (ADR-013), one per tenant. A judicial transfer
 *       to a court deposit in another bank lands here until the treasury sends the TED and records it;</li>
 *   <li>{@code SETTLEMENT}: the single counter-account the backing accounts replaced. Its shards are kept, closed
 *       and at zero, only as history.</li>
 * </ul>
 * {@code policy} is what the account's active blocks let it do (ADR-013); {@code closedAt} is set when it closes.
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
        Instant createdAt,
        AccountPolicy policy,
        Instant closedAt) {

    public enum Kind { CUSTOMER, SETTLEMENT, BACKING, JUDICIAL_TRANSIT }

    /** Whether the account exists; blocks are kept apart (ADR-013). CLOSED is final. */
    public enum Status { ACTIVE, CLOSED }

    public Account {
        policy = policy == null ? AccountPolicy.OPEN : policy;
    }

    public Account(AccountId id, TenantId tenantId, Kind kind, CustomerId customerId, PaymentAccountNumber number,
                   Status status, boolean allowNegativeBalance, Money balance, long version, Instant createdAt) {
        this(id, tenantId, kind, customerId, number, status, allowNegativeBalance, balance, version, createdAt,
                AccountPolicy.OPEN, null);
    }

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

    /**
     * The tenant's "depósitos judiciais a transferir". Like a customer account it holds money owed to someone (the
     * court), so it can never go negative: the treasury cannot record more TEDs than the judicial transfers sent.
     */
    public static Account openJudicialTransit(TenantId tenantId, Instant now) {
        return new Account(AccountId.newId(), tenantId, Kind.JUDICIAL_TRANSIT, null, null,
                Status.ACTIVE, false, Money.ZERO, 0L, now);
    }

    public boolean isActive() {
        return status == Status.ACTIVE;
    }
}
