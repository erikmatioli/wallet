package br.com.walletcore.application.port.out;

import br.com.walletcore.domain.ledger.LedgerTransaction;
import java.util.Optional;

/** Transaction headers, unique per (tenant, idempotency key). Also append-only. */
public interface TransactionJournal {

    /**
     * Registers the transaction unless the idempotency key was already used.
     *
     * @return empty when the transaction was registered now; the existing transaction otherwise.
     *         A concurrent request holding the same key blocks until the first one commits.
     */
    Optional<StoredTransaction> insertIfAbsent(LedgerTransaction transaction, String idempotencyKey, String fingerprint);
}
