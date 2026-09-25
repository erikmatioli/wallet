package br.com.walletcore.domain.event;

import br.com.walletcore.domain.ledger.LedgerEntry;
import br.com.walletcore.domain.ledger.LedgerTransaction;
import br.com.walletcore.domain.shared.UuidV7;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record TransactionPosted(
        UUID eventId,
        UUID tenantId,
        UUID transactionId,
        String type,
        long amountCents,
        String currency,
        String description,
        List<Entry> entries,
        Instant occurredAt) implements DomainEvent {

    public record Entry(UUID accountId, String direction, long amountCents, long sequence, long balanceAfterCents) {
    }

    public static TransactionPosted from(LedgerTransaction tx, List<LedgerEntry> entries) {
        return new TransactionPosted(
                UuidV7.next(),
                tx.tenantId().value(),
                tx.id().value(),
                tx.type().name(),
                tx.amount().cents(),
                "BRL",
                tx.description(),
                entries.stream()
                        .map(e -> new Entry(e.accountId().value(), e.direction().name(), e.amount().cents(),
                                e.sequence(), e.balanceAfter().cents()))
                        .toList(),
                tx.occurredAt());
    }

    @Override
    public String aggregateType() {
        return "transaction";
    }

    @Override
    public String aggregateId() {
        return transactionId.toString();
    }

    @Override
    public String eventType() {
        return "wallet.transaction.posted.v1";
    }
}
