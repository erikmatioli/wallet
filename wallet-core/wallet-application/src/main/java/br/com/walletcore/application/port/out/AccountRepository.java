package br.com.walletcore.application.port.out;

import br.com.walletcore.domain.account.Account;
import br.com.walletcore.domain.shared.AccountId;
import br.com.walletcore.domain.shared.TenantId;
import br.com.walletcore.domain.customer.TaxId;
import java.util.List;
import java.util.Optional;
import java.util.Set;

public interface AccountRepository {

    void insert(Account account);

    Optional<Account> findById(TenantId tenantId, AccountId accountId);

    Optional<Account> findByTaxId(TenantId tenantId, TaxId taxId);

    Optional<Account> findByNumber(TenantId tenantId, String branch, String number, String checkDigit);

    /** Read-model row for the accounts directory: an Account joined with its customer's display name. */
    record AccountDirectoryItem(Account account, String customerName, String documentMasked) {
    }

    /**
     * Page of the tenant's CUSTOMER accounts, newest first. Cursor pagination reuses the fact
     * that {@link AccountId} is a UUIDv7 (time-ordered, see UuidV7): "id < cursor, ordered by id
     * desc" is exactly "created strictly before the last seen account" - no separate sequence
     * column needed, unlike the ledger's {@code sequence_no} (which orders events per account,
     * not accounts themselves).
     *
     * @param cursorExclusive only accounts created before this id are returned; null = from the newest
     */
    List<AccountDirectoryItem> findAccountDirectory(TenantId tenantId, AccountId cursorExclusive, int limit);

    /**
     * Batched lookup of display info (customer name, formatted number) for a known set of
     * account ids - used to enrich a statement page's transfer counterparties in one query
     * instead of one query per row. Ids that don't resolve to a CUSTOMER account of this tenant
     * (shouldn't happen for a genuine transfer counterparty, but degrade gracefully) are simply
     * absent from the result; callers must not assume every id comes back.
     */
    List<AccountDirectoryItem> findByIds(TenantId tenantId, Set<AccountId> ids);

    /** Next value of the account number sequence (may skip values; never repeats). */
    long nextAccountSequence();

    /** Ids of the tenant's settlement shards, in a stable order. */
    List<AccountId> findSettlementAccountIds(TenantId tenantId);

    /**
     * Ids of the tenant's customer accounts most recently touched (by {@code updated_at}), most
     * recent first, capped at {@code limit}. Used by the periodic audit sweep so that, as the
     * ledger grows, it keeps checking a bounded, high-value sample (recently active wallets)
     * instead of scanning every account on every run.
     */
    List<AccountId> findRecentlyActiveCustomerAccountIds(TenantId tenantId, int limit);

    /**
     * Atomically adds {@code deltaCents} to the balance, locking the row until the surrounding
     * transaction ends. The change is only applied if the account exists, has the expected kind,
     * is ACTIVE and (unless it may overdraw) the resulting balance stays non-negative. The check
     * and the update are a single statement, so concurrent movements can never overdraw.
     */
    BalanceUpdateResult applyDelta(TenantId tenantId, AccountId accountId, Account.Kind expectedKind, long deltaCents);
}
