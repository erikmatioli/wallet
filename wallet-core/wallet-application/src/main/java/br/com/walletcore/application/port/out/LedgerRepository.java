package br.com.walletcore.application.port.out;

import br.com.walletcore.domain.ledger.LedgerEntry;
import br.com.walletcore.domain.ledger.TransactionType;
import br.com.walletcore.domain.shared.AccountId;
import br.com.walletcore.domain.shared.TenantId;
import br.com.walletcore.domain.shared.TransactionId;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

/** Append-only. There is intentionally no update or delete operation. */
public interface LedgerRepository {

    void append(List<LedgerEntry> entries);

    /** Newest first. {@code types} null or empty = every type. */
    List<LedgerEntry> findPage(TenantId tenantId, AccountId accountId, Long beforeSequence, int limit,
                               Set<TransactionType> types);

    /**
     * One entry by its position in the account's ledger: a single lookup in the unique
     * (account_id, sequence_no) index. Each step of the closing-balance binary search (ADR-012).
     */
    Optional<LedgerEntry> findBySequence(TenantId tenantId, AccountId accountId, long sequence);

    /** Every leg of one transaction (empty if it does not exist for this tenant). */
    List<LedgerEntry> findByTransaction(TenantId tenantId, TransactionId transactionId);

    /** Visits every entry of the account in ascending sequence order (bounded memory). */
    void forEachInSequence(TenantId tenantId, AccountId accountId, Consumer<LedgerEntry> consumer);
}
