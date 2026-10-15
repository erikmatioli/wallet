package br.com.walletcore.domain.event;

import br.com.walletcore.domain.ledger.LedgerEntry;
import br.com.walletcore.domain.ledger.LedgerTransaction;
import br.com.walletcore.domain.shared.TransactionId;
import br.com.walletcore.domain.shared.UuidV7;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A transaction was posted. Carries what a consumer needs to react without asking the core back (ADR-014): the
 * transaction it relates to, whether it undoes it, and for a Pix whether the other side is a person or a company.
 *
 * @param relatedTransactionId the transaction this one undoes (a withdrawal reversal, a Pix refund, a fee refund),
 *                             returns (a Pix return), carries out (a judicial transit TED) or charges a fee for;
 *                             null when it stands alone
 * @param reversal             it undoes the related transaction: a withdrawal reversal, a PIX_REFUND, a FEE_REFUND
 *                             (a Pix return is not one: the Pix happened and was paid back)
 * @param counterpartyDocumentType for a Pix, CPF or CNPJ of the other side; null otherwise or when unknown
 */
public record TransactionPosted(
        UUID eventId,
        UUID tenantId,
        UUID transactionId,
        String type,
        long amountCents,
        String currency,
        String description,
        List<Entry> entries,
        Instant occurredAt,
        UUID relatedTransactionId,
        boolean reversal,
        String counterpartyDocumentType) implements DomainEvent {

    public record Entry(UUID accountId, String direction, long amountCents, long sequence, long balanceAfterCents) {
    }

    /** What the posting knows besides the ledger: see the record's parameters. */
    public record Facts(TransactionId related, boolean reversal, String counterpartyDocumentType) {

        public static final Facts NONE = new Facts(null, false, null);
    }

    public static TransactionPosted from(LedgerTransaction tx, List<LedgerEntry> entries) {
        return from(tx, entries, Facts.NONE);
    }

    public static TransactionPosted from(LedgerTransaction tx, List<LedgerEntry> entries, Facts facts) {
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
                tx.occurredAt(),
                facts.related() == null ? null : facts.related().value(),
                facts.reversal(),
                facts.counterpartyDocumentType());
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
