package br.com.walletcore.adapter.out.persistence;

import br.com.walletcore.application.port.out.StoredTransaction;
import br.com.walletcore.application.port.out.TransactionJournal;
import br.com.walletcore.domain.ledger.LedgerTransaction;
import br.com.walletcore.domain.ledger.TransactionType;
import br.com.walletcore.domain.shared.Money;
import br.com.walletcore.domain.shared.TransactionId;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcTransactionJournal implements TransactionJournal {

    private final JdbcClient jdbc;

    JdbcTransactionJournal(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * {@code ON CONFLICT DO NOTHING} on the unique (tenant, idempotency key): if a concurrent
     * request is inserting the same key, this statement waits for it to commit or roll back,
     * and only then decides - so two simultaneous retries can never both post.
     */
    @Override
    public Optional<StoredTransaction> insertIfAbsent(LedgerTransaction tx, String idempotencyKey, String fingerprint) {
        int inserted = jdbc.sql("""
                INSERT INTO financial_transaction (id, tenant_id, idempotency_key, fingerprint, type, amount_cents,
                                                   description, status, occurred_at)
                VALUES (:id, :tenant, :key, :fingerprint, :type, :amount, :description, 'POSTED', :at)
                ON CONFLICT (tenant_id, idempotency_key) DO NOTHING""")
                .param("id", tx.id().value())
                .param("tenant", tx.tenantId().value())
                .param("key", idempotencyKey)
                .param("fingerprint", fingerprint)
                .param("type", tx.type().name())
                .param("amount", tx.amount().cents())
                .param("description", tx.description())
                .param("at", Sql.ts(tx.occurredAt()))
                .update();
        if (inserted == 1) {
            return Optional.empty();
        }
        StoredTransaction existing = jdbc.sql("""
                SELECT id, fingerprint, type, amount_cents, description, occurred_at
                  FROM financial_transaction
                 WHERE tenant_id = :tenant AND idempotency_key = :key""")
                .param("tenant", tx.tenantId().value())
                .param("key", idempotencyKey)
                .query((rs, i) -> new StoredTransaction(
                        new TransactionId(Sql.uuid(rs, "id")),
                        rs.getString("fingerprint"),
                        TransactionType.valueOf(rs.getString("type")),
                        Money.ofCents(rs.getLong("amount_cents")),
                        rs.getString("description"),
                        Sql.instant(rs, "occurred_at")))
                .optional()
                .orElseThrow(() -> new IllegalStateException("idempotency key conflict but no row found"));
        return Optional.of(existing);
    }
}
