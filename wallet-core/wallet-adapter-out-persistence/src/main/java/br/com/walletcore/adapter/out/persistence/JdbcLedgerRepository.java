package br.com.walletcore.adapter.out.persistence;

import br.com.walletcore.application.port.out.LedgerRepository;
import br.com.walletcore.domain.ledger.EntryDirection;
import br.com.walletcore.domain.ledger.LedgerEntry;
import br.com.walletcore.domain.ledger.TransactionType;
import br.com.walletcore.domain.shared.AccountId;
import br.com.walletcore.domain.shared.Money;
import br.com.walletcore.domain.shared.TenantId;
import br.com.walletcore.domain.shared.TransactionId;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcLedgerRepository implements LedgerRepository {

    private static final String COLUMNS = """
            id, tenant_id, transaction_id, account_id, sequence_no, direction, amount_cents,
            balance_after_cents, type, description, counterparty_account_id, occurred_at""";
    private static final int REPLAY_CHUNK = 1_000;

    private final JdbcClient jdbc;

    JdbcLedgerRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void append(List<LedgerEntry> entries) {
        for (LedgerEntry e : entries) {
            jdbc.sql("""
                    INSERT INTO ledger_entry (id, tenant_id, transaction_id, account_id, sequence_no, direction,
                                              amount_cents, balance_after_cents, type, description,
                                              counterparty_account_id, occurred_at)
                    VALUES (:id, :tenant, :tx, :account, :seq, :direction, :amount, :balanceAfter, :type, :description,
                            :counterparty, :at)""")
                    .param("id", e.id())
                    .param("tenant", e.tenantId().value())
                    .param("tx", e.transactionId().value())
                    .param("account", e.accountId().value())
                    .param("seq", e.sequence())
                    .param("direction", e.direction().name())
                    .param("amount", e.amount().cents())
                    .param("balanceAfter", e.balanceAfter().cents())
                    .param("type", e.type().name())
                    .param("description", e.description())
                    .param("counterparty", e.counterpartyAccountId().value())
                    .param("at", Sql.ts(e.occurredAt()))
                    .update();
        }
    }

    @Override
    public List<LedgerEntry> findPage(TenantId tenantId, AccountId accountId, Long beforeSequence, int limit,
                                      Set<TransactionType> types) {
        boolean filtered = types != null && !types.isEmpty();
        String sql = "SELECT " + COLUMNS + " FROM ledger_entry WHERE tenant_id = :tenant AND account_id = :account"
                + (beforeSequence != null ? " AND sequence_no < :before" : "")
                + (filtered ? " AND type IN (:types)" : "")
                + " ORDER BY sequence_no DESC LIMIT :limit";
        JdbcClient.StatementSpec spec = jdbc.sql(sql)
                .param("tenant", tenantId.value())
                .param("account", accountId.value())
                .param("limit", limit);
        if (beforeSequence != null) {
            spec = spec.param("before", beforeSequence);
        }
        if (filtered) {
            spec = spec.param("types", types.stream().map(TransactionType::name).toList());
        }
        return spec.query(JdbcLedgerRepository::map).list();
    }

    @Override
    public List<LedgerEntry> findByTransaction(TenantId tenantId, TransactionId transactionId) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM ledger_entry WHERE tenant_id = :tenant AND transaction_id = :tx")
                .param("tenant", tenantId.value())
                .param("tx", transactionId.value())
                .query(JdbcLedgerRepository::map)
                .list();
    }

    @Override
    public void forEachInSequence(TenantId tenantId, AccountId accountId, Consumer<LedgerEntry> consumer) {
        long last = 0;
        while (true) {
            List<LedgerEntry> chunk = jdbc.sql("SELECT " + COLUMNS + """
                     FROM ledger_entry
                    WHERE tenant_id = :tenant AND account_id = :account AND sequence_no > :last
                    ORDER BY sequence_no
                    LIMIT :limit""")
                    .param("tenant", tenantId.value())
                    .param("account", accountId.value())
                    .param("last", last)
                    .param("limit", REPLAY_CHUNK)
                    .query(JdbcLedgerRepository::map)
                    .list();
            if (chunk.isEmpty()) {
                return;
            }
            chunk.forEach(consumer);
            last = chunk.get(chunk.size() - 1).sequence();
        }
    }

    private static LedgerEntry map(ResultSet rs, int rowNum) throws SQLException {
        UUID counterparty = rs.getObject("counterparty_account_id", UUID.class);
        return new LedgerEntry(
                Sql.uuid(rs, "id"),
                new TenantId(Sql.uuid(rs, "tenant_id")),
                new TransactionId(Sql.uuid(rs, "transaction_id")),
                new AccountId(Sql.uuid(rs, "account_id")),
                rs.getLong("sequence_no"),
                EntryDirection.valueOf(rs.getString("direction")),
                Money.ofCents(rs.getLong("amount_cents")),
                Money.ofCents(rs.getLong("balance_after_cents")),
                TransactionType.valueOf(rs.getString("type")),
                rs.getString("description"),
                // Only null for rows written before the V2 migration added this column.
                counterparty == null ? null : new AccountId(counterparty),
                Sql.instant(rs, "occurred_at"));
    }
}
