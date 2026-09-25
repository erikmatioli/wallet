package br.com.walletcore.adapter.out.persistence;

import br.com.walletcore.application.port.out.TenantRepository;
import br.com.walletcore.domain.shared.TenantId;
import br.com.walletcore.domain.tenant.Tenant;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** The tenant table is not tenant-scoped (it is the root of the hierarchy), so it has no RLS. */
@Repository
class JdbcTenantRepository implements TenantRepository {

    private static final String COLUMNS =
            "id, client_id, secret_hash, name, ispb, branch, scopes, status, created_at";

    private final JdbcClient jdbc;

    JdbcTenantRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<Tenant> findById(TenantId id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM tenant WHERE id = :id")
                .param("id", id.value())
                .query(JdbcTenantRepository::map)
                .optional();
    }

    @Override
    public Optional<Tenant> findByClientId(String clientId) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM tenant WHERE client_id = :clientId")
                .param("clientId", clientId)
                .query(JdbcTenantRepository::map)
                .optional();
    }

    @Override
    public void insert(Tenant tenant) {
        jdbc.sql("""
                INSERT INTO tenant (id, client_id, secret_hash, name, ispb, branch, scopes, status, created_at)
                VALUES (:id, :clientId, :secretHash, :name, :ispb, :branch, :scopes, :status, :createdAt)""")
                .param("id", tenant.id().value())
                .param("clientId", tenant.clientId())
                .param("secretHash", tenant.secretHash())
                .param("name", tenant.name())
                .param("ispb", tenant.ispb())
                .param("branch", tenant.branch())
                .param("scopes", tenant.scopes().stream().sorted().collect(Collectors.joining(" ")))
                .param("status", tenant.status().name())
                .param("createdAt", Sql.ts(tenant.createdAt()))
                .update();
    }

    /** {@code tenant} has no Row Level Security (it is the root of the hierarchy), so a plain scan is safe. */
    @Override
    public List<TenantId> findAllActiveIds() {
        return jdbc.sql("SELECT id FROM tenant WHERE status = 'ACTIVE' ORDER BY id")
                .query((rs, i) -> new TenantId(Sql.uuid(rs, "id")))
                .list();
    }

    private static Tenant map(ResultSet rs, int rowNum) throws SQLException {
        Set<String> scopes = Arrays.stream(rs.getString("scopes").split(" ")).filter(s -> !s.isBlank())
                .collect(Collectors.toUnmodifiableSet());
        return new Tenant(
                new TenantId(Sql.uuid(rs, "id")),
                rs.getString("client_id"),
                rs.getString("secret_hash"),
                rs.getString("name"),
                rs.getString("ispb"),
                rs.getString("branch"),
                scopes,
                Tenant.Status.valueOf(rs.getString("status")),
                Sql.instant(rs, "created_at"));
    }
}
