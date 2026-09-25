package br.com.walletcore.domain.ledger;

import br.com.walletcore.domain.shared.AccountId;
import br.com.walletcore.domain.shared.Money;
import br.com.walletcore.domain.shared.TenantId;
import br.com.walletcore.domain.shared.TransactionId;
import java.time.Instant;
import java.util.UUID;

/**
 * Immutable ledger fact. {@code sequence} is gapless per account (1, 2, 3...) and
 * {@code balanceAfter} is the account balance right after this entry, so the balance can be
 * rebuilt - and every step verified - by replaying entries in sequence order.
 */
public record LedgerEntry(
        UUID id,
        TenantId tenantId,
        TransactionId transactionId,
        AccountId accountId,
        long sequence,
        EntryDirection direction,
        Money amount,
        Money balanceAfter,
        TransactionType type,
        String description,
        Instant occurredAt) {

    public long signedCents() {
        return direction == EntryDirection.CREDIT ? amount.cents() : -amount.cents();
    }
}
