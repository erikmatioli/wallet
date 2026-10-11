package br.com.walletcore.adapter.out.persistence;

import br.com.walletcore.application.port.out.AccountStatusEventRepository;
import br.com.walletcore.domain.account.Account;
import br.com.walletcore.domain.account.AccountStatusEvent;
import br.com.walletcore.domain.account.Actor;
import br.com.walletcore.domain.shared.AccountId;
import br.com.walletcore.domain.shared.TenantId;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcAccountStatusEventRepository implements AccountStatusEventRepository {

    private final JdbcClient jdbc;

    JdbcAccountStatusEventRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * The per-tenant lock is held until the transaction ends and is taken before the seq is drawn: a second writer of
     * the tenant draws its seq only after the first committed, so the feed never shows n+1 while n is still to
     * come. Openings and closings are rare enough per tenant for this to cost nothing.
     */
    @Override
    public void append(TenantId tenantId, AccountStatusEvent e) {
        jdbc.sql("SELECT pg_advisory_xact_lock(hashtextextended(:key, 0))")
                .param("key", "account-status-event:" + tenantId.value())
                .query((rs, i) -> 1)
                .single();
        jdbc.sql("""
                INSERT INTO account_status_event (tenant_id, account_id, from_status, to_status, occurred_at, operator,
                                                  client_id, reason, note)
                VALUES (:tenant, :account, :from, :to, :at, :operator, :client, :reason, :note)""")
                .param("tenant", tenantId.value())
                .param("account", e.accountId().value())
                .param("from", e.from() == null ? null : e.from().name(), Types.VARCHAR)
                .param("to", e.to().name())
                .param("at", Sql.ts(e.occurredAt()))
                .param("operator", e.actor().operator())
                .param("client", e.actor().clientId())
                .param("reason", e.reason() == null ? null : e.reason().name(), Types.VARCHAR)
                .param("note", e.note(), Types.VARCHAR)
                .update();
    }

    @Override
    public List<AccountStatusEvent> findByAccount(TenantId tenantId, AccountId accountId) {
        return jdbc.sql("""
                SELECT seq, account_id, from_status, to_status, occurred_at, operator, client_id, reason, note
                  FROM account_status_event
                 WHERE tenant_id = :tenant AND account_id = :account
                 ORDER BY seq""")
                .param("tenant", tenantId.value())
                .param("account", accountId.value())
                .query(JdbcAccountStatusEventRepository::map)
                .list();
    }

    @Override
    public List<CcsAccountEvent> findAfter(TenantId tenantId, long afterSeq, int limit) {
        return jdbc.sql("""
                SELECT e.seq, e.account_id, e.to_status, e.occurred_at, c.name, c.tax_id_type, c.tax_id,
                       a.ispb, a.branch, a.account_number, a.check_digit, a.account_type
                  FROM account_status_event e
                  JOIN account a ON a.id = e.account_id
                  JOIN customer c ON c.id = a.customer_id
                 WHERE e.tenant_id = :tenant AND e.seq > :after
                 ORDER BY e.seq
                 LIMIT :limit""")
                .param("tenant", tenantId.value())
                .param("after", afterSeq)
                .param("limit", limit)
                .query((rs, i) -> new CcsAccountEvent(
                        rs.getLong("seq"),
                        new AccountId(Sql.uuid(rs, "account_id")),
                        "ACTIVE".equals(rs.getString("to_status")) ? CcsAccountEvent.Kind.OPENED
                                : CcsAccountEvent.Kind.CLOSED,
                        Sql.instant(rs, "occurred_at"),
                        rs.getString("name"),
                        rs.getString("tax_id_type"),
                        rs.getString("tax_id"),
                        rs.getString("ispb"),
                        rs.getString("branch"),
                        rs.getString("account_number"),
                        rs.getString("check_digit"),
                        rs.getString("account_type")))
                .list();
    }

    private static AccountStatusEvent map(ResultSet rs, int rowNum) throws SQLException {
        String from = rs.getString("from_status");
        String reason = rs.getString("reason");
        return new AccountStatusEvent(
                rs.getLong("seq"),
                new AccountId(Sql.uuid(rs, "account_id")),
                from == null ? null : Account.Status.valueOf(from),
                Account.Status.valueOf(rs.getString("to_status")),
                Sql.instant(rs, "occurred_at"),
                new Actor(rs.getString("operator"), rs.getString("client_id")),
                reason == null ? null : AccountStatusEvent.CloseReason.valueOf(reason),
                rs.getString("note"));
    }
}
