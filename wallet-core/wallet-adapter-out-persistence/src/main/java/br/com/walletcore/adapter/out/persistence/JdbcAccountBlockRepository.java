package br.com.walletcore.adapter.out.persistence;

import br.com.walletcore.application.port.out.AccountBlockRepository;
import br.com.walletcore.domain.account.AccountBlock;
import br.com.walletcore.domain.account.Actor;
import br.com.walletcore.domain.shared.AccountId;
import br.com.walletcore.domain.shared.BlockId;
import br.com.walletcore.domain.shared.TenantId;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcAccountBlockRepository implements AccountBlockRepository {

    private static final String COLUMNS = """
            id, tenant_id, account_id, type, reason, note, court_process, court_protocol, created_operator,
            created_client, created_at, released_at, released_operator, released_client, release_note""";

    private final JdbcClient jdbc;

    JdbcAccountBlockRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void insert(AccountBlock b) {
        jdbc.sql("""
                INSERT INTO account_block (id, tenant_id, account_id, type, reason, note, court_process, court_protocol,
                                           created_operator, created_client, created_at)
                VALUES (:id, :tenant, :account, :type, :reason, :note, :process, :protocol, :operator, :client, :at)""")
                .param("id", b.id().value())
                .param("tenant", b.tenantId().value())
                .param("account", b.accountId().value())
                .param("type", b.type().name())
                .param("reason", b.reason().name())
                .param("note", b.note(), Types.VARCHAR)
                .param("process", b.court() == null ? null : b.court().process(), Types.VARCHAR)
                .param("protocol", b.court() == null ? null : b.court().protocol(), Types.VARCHAR)
                .param("operator", b.createdBy().operator())
                .param("client", b.createdBy().clientId())
                .param("at", Sql.ts(b.createdAt()))
                .update();
    }

    @Override
    public Optional<AccountBlock> findById(TenantId tenantId, BlockId id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM account_block WHERE tenant_id = :tenant AND id = :id")
                .param("tenant", tenantId.value())
                .param("id", id.value())
                .query(JdbcAccountBlockRepository::map)
                .optional();
    }

    @Override
    public List<AccountBlock> findByAccount(TenantId tenantId, AccountId accountId, boolean activeOnly) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM account_block WHERE tenant_id = :tenant AND account_id = :account"
                        + (activeOnly ? " AND released_at IS NULL" : "") + " ORDER BY created_at DESC, id DESC")
                .param("tenant", tenantId.value())
                .param("account", accountId.value())
                .query(JdbcAccountBlockRepository::map)
                .list();
    }

    @Override
    public void markReleased(AccountBlock released) {
        AccountBlock.Release r = released.release();
        // "released_at IS NULL": a release lost to a concurrent one changes nothing, and the trigger refuses a second.
        int updated = jdbc.sql("""
                UPDATE account_block
                   SET released_at = :at, released_operator = :operator, released_client = :client, release_note = :note
                 WHERE tenant_id = :tenant AND id = :id AND released_at IS NULL""")
                .param("at", Sql.ts(r.at()))
                .param("operator", r.by().operator())
                .param("client", r.by().clientId())
                .param("note", r.note())
                .param("tenant", released.tenantId().value())
                .param("id", released.id().value())
                .update();
        if (updated != 1) {
            throw new IllegalStateException("block " + released.id() + " was not active");
        }
    }

    private static AccountBlock map(ResultSet rs, int rowNum) throws SQLException {
        String process = rs.getString("court_process");
        Instant releasedAt = Sql.instantOrNull(rs, "released_at");
        return new AccountBlock(
                new BlockId(Sql.uuid(rs, "id")),
                new TenantId(Sql.uuid(rs, "tenant_id")),
                new AccountId(Sql.uuid(rs, "account_id")),
                AccountBlock.Type.valueOf(rs.getString("type")),
                AccountBlock.Reason.valueOf(rs.getString("reason")),
                rs.getString("note"),
                process == null ? null : new AccountBlock.CourtOrder(process, rs.getString("court_protocol")),
                new Actor(rs.getString("created_operator"), rs.getString("created_client")),
                Sql.instant(rs, "created_at"),
                releasedAt == null ? null : new AccountBlock.Release(
                        new Actor(rs.getString("released_operator"), rs.getString("released_client")), releasedAt,
                        rs.getString("release_note")));
    }
}
