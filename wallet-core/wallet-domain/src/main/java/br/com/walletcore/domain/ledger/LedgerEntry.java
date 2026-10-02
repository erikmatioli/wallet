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
 *
 * <p>{@code counterpartyAccountId} is the account on the other leg of the same transaction -
 * denormalized onto every entry at write time (see {@code MoveMoneyService.post}) so a statement
 * never needs a second query to show "who was this transfer with". It is always populated at the
 * domain/storage level, for every transaction type, but the REST layer only ever returns it for
 * {@code TRANSFER} entries: for a deposit or withdrawal the counterparty is an internal
 * settlement account, and those must stay invisible to clients (see AccountController /
 * QueryAccountService, which already filter settlement accounts out of every client-facing read).
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
        AccountId counterpartyAccountId,
        Instant occurredAt) {

    public long signedCents() {
        return direction == EntryDirection.CREDIT ? amount.cents() : -amount.cents();
    }
}
