package br.com.walletcore.application.port.out;

import br.com.walletcore.domain.account.Account;
import br.com.walletcore.domain.backing.BackingAccount;
import br.com.walletcore.domain.backing.RevenueWithdrawal;
import br.com.walletcore.domain.backing.TreasuryReference;
import br.com.walletcore.domain.shared.AccountId;
import br.com.walletcore.domain.shared.BackingAccountId;
import br.com.walletcore.domain.shared.Money;
import br.com.walletcore.domain.shared.TenantId;
import br.com.walletcore.domain.shared.TransactionId;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Backing accounts (contas de lastro, ADR-011) and the ledger shards that hold their balance. */
public interface BackingAccountRepository {

    /** The backing account and its shards (ledger accounts of kind BACKING), linked to it. */
    void insert(BackingAccount account, List<Account> shards);

    /** Every backing account of the tenant, the defaults first, then by creation. */
    List<BackingAccount> findAll(TenantId tenantId);

    Optional<BackingAccount> findById(TenantId tenantId, BackingAccountId id);

    /** The shards of a backing account, in a stable order (by id). */
    List<AccountId> findShardIds(TenantId tenantId, BackingAccountId id);

    /** Each shard of the tenant and the backing account it belongs to. */
    Map<AccountId, BackingAccountId> findShardOwners(TenantId tenantId);

    /** The sum of each backing account's shard balances (a few rows per backing account, never the ledger). */
    Map<BackingAccountId, Long> sumBalances(TenantId tenantId);

    /**
     * The sum of the shard balances of one backing account, with its shards locked until the transaction ends:
     * for a check that must not race with the movements that change it (the coverage limit).
     */
    long lockAndSumBalance(TenantId tenantId, BackingAccountId id);

    void insertTreasuryReference(TenantId tenantId, TransactionId transactionId, TreasuryReference reference);

    /**
     * The receipt and note of each treasury transfer and of each revenue withdrawal (ADR-015) among {@code ids}:
     * what a backing account's statement shows next to them.
     */
    Map<TransactionId, TreasuryReference> findTreasuryReferences(TenantId tenantId, Collection<TransactionId> ids);

    void insertRevenueWithdrawal(TenantId tenantId, RevenueWithdrawal withdrawal);

    Map<TransactionId, RevenueWithdrawal> findRevenueWithdrawals(TenantId tenantId, Collection<TransactionId> ids);

    /** The revenue withdrawn at or after {@code since}: a few rows, for the month's total. */
    Money sumRevenueWithdrawnSince(TenantId tenantId, Instant since);
}
