package br.com.walletcore.adapter.out.persistence;

import br.com.walletcore.application.port.out.JudicialTransferRepository;
import br.com.walletcore.domain.account.Actor;
import br.com.walletcore.domain.shared.AccountId;
import br.com.walletcore.domain.shared.BlockId;
import br.com.walletcore.domain.shared.Money;
import br.com.walletcore.domain.shared.TenantId;
import br.com.walletcore.domain.shared.TransactionId;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcJudicialTransferRepository implements JudicialTransferRepository {

    private final JdbcClient jdbc;

    JdbcJudicialTransferRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void insert(TenantId tenantId, Detail d) {
        jdbc.sql("""
                INSERT INTO judicial_transfer_detail (transaction_id, tenant_id, kind, account_id, block_id, court_process,
                                                      court_protocol, to_transit, related_transaction_id, operator,
                                                      client_id, note, document)
                VALUES (:tx, :tenant, :kind, :account, :block, :process, :protocol, :toTransit, :related, :operator,
                        :client, :note, :document)""")
                .param("tx", d.transactionId().value())
                .param("tenant", tenantId.value())
                .param("kind", d.kind().name())
                .param("account", d.accountId() == null ? null : d.accountId().value(), Types.OTHER)
                .param("block", d.blockId().value())
                .param("process", d.process())
                .param("protocol", d.protocol(), Types.VARCHAR)
                .param("toTransit", d.toTransit())
                .param("related", d.related() == null ? null : d.related().value(), Types.OTHER)
                .param("operator", d.actor().operator())
                .param("client", d.actor().clientId())
                .param("note", d.note(), Types.VARCHAR)
                .param("document", d.document(), Types.VARCHAR)
                .update();
    }

    @Override
    public Optional<Detail> findByTransaction(TenantId tenantId, TransactionId transactionId) {
        return jdbc.sql("""
                SELECT transaction_id, kind, account_id, block_id, court_process, court_protocol, to_transit,
                       related_transaction_id, operator, client_id, note, document
                  FROM judicial_transfer_detail
                 WHERE tenant_id = :tenant AND transaction_id = :tx""")
                .param("tenant", tenantId.value())
                .param("tx", transactionId.value())
                .query(JdbcJudicialTransferRepository::map)
                .optional();
    }

    @Override
    public void lock(TenantId tenantId, TransactionId transferId) {
        jdbc.sql("SELECT pg_advisory_xact_lock(hashtextextended(:key, 0))")
                .param("key", "judicial-transfer:" + transferId.value())
                .query((rs, i) -> 1)
                .single();
    }

    @Override
    public long sumSent(TenantId tenantId, TransactionId transferId) {
        return jdbc.sql("""
                SELECT COALESCE(SUM(t.amount_cents), 0)
                  FROM judicial_transfer_detail d
                  JOIN financial_transaction t ON t.id = d.transaction_id
                 WHERE d.tenant_id = :tenant AND d.related_transaction_id = :tx AND d.kind = 'TRANSIT_OUT'""")
                .param("tenant", tenantId.value())
                .param("tx", transferId.value())
                .query(Long.class)
                .single();
    }

    @Override
    public List<PendingTransit> findPendingTransit(TenantId tenantId) {
        return jdbc.sql("""
                SELECT d.transaction_id, d.account_id, d.court_process, d.court_protocol, t.amount_cents, t.occurred_at,
                       COALESCE((SELECT SUM(ot.amount_cents)
                                   FROM judicial_transfer_detail o
                                   JOIN financial_transaction ot ON ot.id = o.transaction_id
                                  WHERE o.related_transaction_id = d.transaction_id AND o.kind = 'TRANSIT_OUT'), 0) AS sent
                  FROM judicial_transfer_detail d
                  JOIN financial_transaction t ON t.id = d.transaction_id
                 WHERE d.tenant_id = :tenant AND d.kind = 'TRANSFER' AND d.to_transit
                 ORDER BY t.occurred_at, d.transaction_id""")
                .param("tenant", tenantId.value())
                .query((rs, i) -> new PendingTransit(
                        new TransactionId(Sql.uuid(rs, "transaction_id")),
                        new AccountId(Sql.uuid(rs, "account_id")),
                        rs.getString("court_process"),
                        rs.getString("court_protocol"),
                        Money.ofCents(rs.getLong("amount_cents")),
                        Money.ofCents(rs.getLong("sent")),
                        Sql.instant(rs, "occurred_at")))
                .list()
                .stream()
                .filter(p -> p.remaining().isPositive())
                .toList();
    }

    private static Detail map(ResultSet rs, int rowNum) throws SQLException {
        UUID account = rs.getObject("account_id", UUID.class);
        UUID related = rs.getObject("related_transaction_id", UUID.class);
        return new Detail(
                new TransactionId(Sql.uuid(rs, "transaction_id")),
                Kind.valueOf(rs.getString("kind")),
                account == null ? null : new AccountId(account),
                new BlockId(Sql.uuid(rs, "block_id")),
                rs.getString("court_process"),
                rs.getString("court_protocol"),
                rs.getBoolean("to_transit"),
                related == null ? null : new TransactionId(related),
                new Actor(rs.getString("operator"), rs.getString("client_id")),
                rs.getString("note"),
                rs.getString("document"));
    }
}
