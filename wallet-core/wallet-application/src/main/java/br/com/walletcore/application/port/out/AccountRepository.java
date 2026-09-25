package br.com.walletcore.application.port.out;

import br.com.walletcore.domain.account.Account;
import br.com.walletcore.domain.shared.AccountId;
import br.com.walletcore.domain.shared.TenantId;
import java.util.List;
import java.util.Optional;

public interface AccountRepository {

    void insert(Account account);

    Optional<Account> findById(TenantId tenantId, AccountId accountId);

    Optional<Account> findByNumber(TenantId tenantId, String branch, String number, String checkDigit);

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
