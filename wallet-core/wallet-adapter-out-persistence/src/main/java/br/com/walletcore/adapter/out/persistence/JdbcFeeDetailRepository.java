package br.com.walletcore.adapter.out.persistence;

import br.com.walletcore.application.port.out.FeeDetailRepository;
import br.com.walletcore.domain.shared.TenantId;
import br.com.walletcore.domain.shared.TransactionId;
import java.sql.Types;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcFeeDetailRepository implements FeeDetailRepository {

    private final JdbcClient jdbc;

    JdbcFeeDetailRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void insert(TenantId tenantId, TransactionId transactionId, Detail d) {
        jdbc.sql("""
                INSERT INTO fee_detail (transaction_id, tenant_id, operation, source_transaction_id, reference)
                VALUES (:tx, :tenant, :operation, :source, :reference)""")
                .param("tx", transactionId.value())
                .param("tenant", tenantId.value())
                .param("operation", d.operation())
                .param("source", d.source() == null ? null : d.source().value(), Types.OTHER)
                .param("reference", d.reference(), Types.VARCHAR)
                .update();
    }

    @Override
    public Optional<Detail> findByTransaction(TenantId tenantId, TransactionId transactionId) {
        return jdbc.sql("""
                SELECT operation, source_transaction_id, reference
                  FROM fee_detail
                 WHERE tenant_id = :tenant AND transaction_id = :tx""")
                .param("tenant", tenantId.value())
                .param("tx", transactionId.value())
                .query((rs, i) -> {
                    UUID source = rs.getObject("source_transaction_id", UUID.class);
                    return new Detail(rs.getString("operation"), source == null ? null : new TransactionId(source),
                            rs.getString("reference"));
                })
                .optional();
    }
}
