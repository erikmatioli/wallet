package br.com.walletcore.application.port.out;

import br.com.walletcore.domain.ledger.TransactionType;
import br.com.walletcore.domain.shared.Money;
import br.com.walletcore.domain.shared.TransactionId;
import java.time.Instant;

/** A previously posted transaction, found through its idempotency key. */
public record StoredTransaction(TransactionId id, String fingerprint, TransactionType type, Money amount,
                                String description, Instant occurredAt) {
}
