package br.com.walletcore.adapter.out.persistence;

import br.com.walletcore.application.port.out.PixDetailRepository;
import br.com.walletcore.domain.exception.ConflictException;
import br.com.walletcore.domain.ledger.TransactionType;
import br.com.walletcore.domain.pix.PixCounterparty;
import br.com.walletcore.domain.pix.PixDetail;
import br.com.walletcore.domain.shared.TenantId;
import br.com.walletcore.domain.shared.TransactionId;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcPixDetailRepository implements PixDetailRepository {

    private static final String COLUMNS = """
            transaction_id, type, end_to_end_id, return_id, related_transaction_id, counterparty_name,
            counterparty_tax_id_masked, counterparty_ispb, counterparty_branch, counterparty_account,
            counterparty_account_type, reason_code, remittance_info""";

    private final JdbcClient jdbc;

    JdbcPixDetailRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void insert(TenantId tenantId, TransactionId transactionId, PixDetail d) {
        PixCounterparty c = d.counterparty();
        try {
            jdbc.sql("""
                    INSERT INTO pix_transaction_detail (transaction_id, tenant_id, type, end_to_end_id, return_id,
                                                        related_transaction_id, counterparty_name,
                                                        counterparty_tax_id_masked, counterparty_ispb,
                                                        counterparty_branch, counterparty_account,
                                                        counterparty_account_type, reason_code, remittance_info)
                    VALUES (:tx, :tenant, :type, :e2e, :returnId, :related, :name, :taxId, :ispb, :branch, :account,
                            :accountType, :reason, :remittance)""")
                    .param("tx", transactionId.value())
                    .param("tenant", tenantId.value())
                    .param("type", d.type().name())
                    .param("e2e", d.endToEndId())
                    .param("returnId", d.returnId(), Types.VARCHAR)
                    .param("related", d.relatedTransactionId() == null ? null : d.relatedTransactionId().value(),
                            Types.OTHER)
                    .param("name", c.name())
                    .param("taxId", c.taxIdMasked())
                    .param("ispb", c.ispb())
                    .param("branch", c.branch(), Types.VARCHAR)
                    .param("account", c.account())
                    .param("accountType", c.accountType(), Types.VARCHAR)
                    .param("reason", d.reasonCode(), Types.VARCHAR)
                    .param("remittance", d.remittanceInfo(), Types.VARCHAR)
                    .update();
        } catch (DuplicateKeyException e) {
            // Two requests with different Idempotency-Keys for the same Pix, racing past the
            // service's own check: the unique index is the last word.
            throw new ConflictException("PIX_ALREADY_POSTED", "this Pix was already posted with another Idempotency-Key");
        }
    }

    @Override
    public Optional<PixDetail> findByTransaction(TenantId tenantId, TransactionId transactionId) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM pix_transaction_detail WHERE tenant_id = :tenant AND transaction_id = :tx")
                .param("tenant", tenantId.value())
                .param("tx", transactionId.value())
                .query(JdbcPixDetailRepository::map)
                .optional();
    }

    @Override
    public Map<TransactionId, PixDetail> findByTransactions(TenantId tenantId, Collection<TransactionId> transactionIds) {
        if (transactionIds.isEmpty()) {
            return Map.of();
        }
        return jdbc.sql("SELECT " + COLUMNS + """
                 FROM pix_transaction_detail
                WHERE tenant_id = :tenant AND transaction_id IN (:ids)""")
                .param("tenant", tenantId.value())
                .param("ids", transactionIds.stream().map(TransactionId::value).toList())
                .query((rs, i) -> Map.entry(new TransactionId(Sql.uuid(rs, "transaction_id")), map(rs, i)))
                .list()
                .stream()
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    @Override
    public boolean exists(TenantId tenantId, TransactionType type, String endToEndId, String returnId) {
        return jdbc.sql("""
                SELECT EXISTS (SELECT 1 FROM pix_transaction_detail
                                WHERE tenant_id = :tenant AND type = :type AND end_to_end_id = :e2e
                                  AND return_id IS NOT DISTINCT FROM :returnId)""")
                .param("tenant", tenantId.value())
                .param("type", type.name())
                .param("e2e", endToEndId)
                .param("returnId", returnId, Types.VARCHAR)
                .query(Boolean.class)
                .single();
    }

    @Override
    public long sumRelated(TenantId tenantId, TransactionId related, TransactionType type) {
        return jdbc.sql("""
                SELECT COALESCE(SUM(t.amount_cents), 0)
                  FROM pix_transaction_detail d
                  JOIN financial_transaction t ON t.id = d.transaction_id
                 WHERE d.tenant_id = :tenant AND d.related_transaction_id = :related AND d.type = :type""")
                .param("tenant", tenantId.value())
                .param("related", related.value())
                .param("type", type.name())
                .query(Long.class)
                .single();
    }

    /**
     * Transaction-scoped advisory lock keyed on the original's id: released at commit or rollback,
     * and needs no UPDATE privilege on the (append-only) tables, unlike SELECT ... FOR UPDATE.
     */
    @Override
    public void lockOriginal(TenantId tenantId, TransactionId original) {
        jdbc.sql("SELECT pg_advisory_xact_lock(hashtextextended(:key, 0))")
                .param("key", "pix-original:" + original.value())
                .query((rs, i) -> 1)
                .single();
    }

    private static PixDetail map(ResultSet rs, int rowNum) throws SQLException {
        UUID related = rs.getObject("related_transaction_id", UUID.class);
        return new PixDetail(
                TransactionType.valueOf(rs.getString("type")),
                rs.getString("end_to_end_id"),
                rs.getString("return_id"),
                related == null ? null : new TransactionId(related),
                new PixCounterparty(
                        rs.getString("counterparty_name"),
                        rs.getString("counterparty_tax_id_masked"),
                        rs.getString("counterparty_ispb"),
                        rs.getString("counterparty_branch"),
                        rs.getString("counterparty_account"),
                        rs.getString("counterparty_account_type")),
                rs.getString("reason_code"),
                rs.getString("remittance_info"));
    }
}
