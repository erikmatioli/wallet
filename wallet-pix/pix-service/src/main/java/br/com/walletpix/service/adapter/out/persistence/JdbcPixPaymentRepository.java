package br.com.walletpix.service.adapter.out.persistence;

import br.com.walletpix.service.application.port.PixPorts.ConcurrentUpdateException;
import br.com.walletpix.service.application.port.PixPorts.PixPaymentRepository;
import br.com.walletpix.service.domain.PixPayment;
import br.com.walletpix.service.domain.PixPayment.PartyAccount;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcPixPaymentRepository implements PixPaymentRepository {

    private static final String SELECT = "SELECT * FROM pix_payment ";

    private final JdbcClient jdbc;

    JdbcPixPaymentRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<PixPayment> find(String endToEndId, PixPayment.Direction direction) {
        return jdbc.sql(SELECT + "WHERE end_to_end_id = :e2e AND direction = :direction")
                .param("e2e", endToEndId)
                .param("direction", direction.name())
                .query(JdbcPixPaymentRepository::map)
                .optional();
    }

    @Override
    public Optional<PixPayment> findByPacs008MsgId(String msgId) {
        return jdbc.sql(SELECT + "WHERE pacs008_msg_id = :msgId").param("msgId", msgId)
                .query(JdbcPixPaymentRepository::map).optional();
    }

    @Override
    public Optional<PixPayment> findByRequestId(String requestId) {
        return jdbc.sql(SELECT + "WHERE request_id = :requestId").param("requestId", requestId)
                .query(JdbcPixPaymentRepository::map).optional();
    }

    @Override
    public void insert(PixPayment p) {
        jdbc.sql("""
                INSERT INTO pix_payment (id, end_to_end_id, direction, status, ispb, counterpart_ispb, pacs008_msg_id,
                    amount_cents, payer_name, payer_tax_id, payer_branch, payer_account,
                    payee_name, payee_tax_id, payee_branch, payee_account, wallet_account_id, debit_transaction_id,
                    request_id, reason_code, description, wallet_transaction_id, version, created_at, updated_at)
                VALUES (:id, :e2e, :direction, :status, :ispb, :counterpart, :msgId, :amount,
                    :payerName, :payerTaxId, :payerBranch, :payerAccount,
                    :payeeName, :payeeTaxId, :payeeBranch, :payeeAccount, :walletAccount, :debitTx, :requestId,
                    :reason, :description, :walletTx, :version, :createdAt, :updatedAt)""")
                .param("id", p.id())
                .param("e2e", p.endToEndId())
                .param("direction", p.direction().name())
                .param("status", p.status().name())
                .param("ispb", p.ispb())
                .param("counterpart", p.counterpartIspb())
                .param("msgId", p.pacs008MsgId())
                .param("amount", p.amountCents())
                .param("payerName", p.payer().name(), Types.VARCHAR)
                .param("payerTaxId", p.payer().taxId(), Types.VARCHAR)
                .param("payerBranch", p.payer().branch(), Types.VARCHAR)
                .param("payerAccount", p.payer().accountNumber(), Types.VARCHAR)
                .param("payeeName", p.payee().name(), Types.VARCHAR)
                .param("payeeTaxId", p.payee().taxId(), Types.VARCHAR)
                .param("payeeBranch", p.payee().branch(), Types.VARCHAR)
                .param("payeeAccount", p.payee().accountNumber(), Types.VARCHAR)
                .param("walletAccount", p.walletAccountId(), Types.OTHER)
                .param("debitTx", p.debitTransactionId(), Types.OTHER)
                .param("requestId", p.requestId(), Types.VARCHAR)
                .param("reason", p.reasonCode(), Types.VARCHAR)
                .param("description", p.description(), Types.VARCHAR)
                .param("walletTx", p.walletTransactionId(), Types.OTHER)
                .param("version", p.version())
                .param("createdAt", Timestamp.from(p.createdAt()))
                .param("updatedAt", Timestamp.from(p.updatedAt()))
                .update();
    }

    @Override
    public void update(PixPayment p) {
        int updated = jdbc.sql("""
                UPDATE pix_payment
                   SET status = :status, reason_code = :reason, wallet_transaction_id = :walletTx,
                       version = version + 1, updated_at = :updatedAt
                 WHERE id = :id AND version = :version""")
                .param("status", p.status().name())
                .param("reason", p.reasonCode(), Types.VARCHAR)
                .param("walletTx", p.walletTransactionId(), Types.OTHER)
                .param("updatedAt", Timestamp.from(p.updatedAt()))
                .param("id", p.id())
                .param("version", p.version())
                .update();
        if (updated != 1) {
            throw new ConcurrentUpdateException("Pix " + p.endToEndId() + " (" + p.direction()
                    + ") changed concurrently; the message will be retried");
        }
    }

    private static PixPayment map(ResultSet rs, int row) throws SQLException {
        return new PixPayment(
                rs.getObject("id", UUID.class),
                rs.getString("end_to_end_id"),
                PixPayment.Direction.valueOf(rs.getString("direction")),
                PixPayment.Status.valueOf(rs.getString("status")),
                rs.getString("ispb"),
                rs.getString("counterpart_ispb"),
                rs.getString("pacs008_msg_id"),
                rs.getLong("amount_cents"),
                new PartyAccount(rs.getString("payer_name"), rs.getString("payer_tax_id"),
                        rs.getString("payer_branch"), rs.getString("payer_account")),
                new PartyAccount(rs.getString("payee_name"), rs.getString("payee_tax_id"),
                        rs.getString("payee_branch"), rs.getString("payee_account")),
                rs.getObject("wallet_account_id", UUID.class),
                rs.getObject("debit_transaction_id", UUID.class),
                rs.getString("request_id"),
                rs.getString("reason_code"),
                rs.getString("description"),
                rs.getObject("wallet_transaction_id", UUID.class),
                rs.getLong("version"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant());
    }
}
