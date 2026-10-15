package br.com.walletcore.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** ADR-014's V11 on a tenant that existed before it: its revenue account, the fee scopes and the new rules. */
class FeesMigrationTest {

    private static PostgreSQLContainer postgres;

    private final UUID tenant = UUID.randomUUID();

    @BeforeAll
    static void start() {
        Assumptions.assumeTrue(DockerClientFactory.instance().isDockerAvailable(), "Docker is not available");
        postgres = new PostgreSQLContainer("postgres:17-alpine");
        postgres.start();
    }

    @AfterAll
    static void stop() {
        if (postgres != null) {
            postgres.stop();
        }
    }

    private Flyway flyway(String target) {
        return Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/migration")
                .target(target)
                .load();
    }

    private Connection connect() throws SQLException {
        Connection c = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        try (Statement st = c.createStatement()) {
            st.execute("SELECT set_config('app.tenant_id', '" + tenant + "', false)");
        }
        return c;
    }

    @Test
    void anExistingTenantGetsItsRevenueAccountAndTheFeeScopes() throws Exception {
        flyway("10").migrate();
        try (Connection c = connect(); Statement st = c.createStatement()) {
            st.execute("INSERT INTO tenant VALUES ('" + tenant + "', 'old-tenant', 'x', 'Old', '12345678', '0001',"
                    + " 'ledger:write accounts:read', 'ACTIVE', now())");
        }

        flyway("11").migrate();

        try (Connection c = connect(); Statement st = c.createStatement()) {
            assertThat(single(st, "SELECT count(*) FROM account WHERE kind = 'TENANT_REVENUE' AND NOT allow_negative"))
                    .isEqualTo(1);
            try (ResultSet rs = st.executeQuery("SELECT scopes FROM tenant")) {
                rs.next();
                assertThat(rs.getString(1).split(" ")).contains("fees:read", "fees:admin", "fees:write");
            }
            // A second revenue account for the same tenant is refused.
            assertThatThrownBy(() -> st.execute("INSERT INTO account (id, tenant_id, kind, status, allow_negative,"
                    + " balance_cents, version, created_at, updated_at) VALUES ('" + UUID.randomUUID() + "', '" + tenant
                    + "', 'TENANT_REVENUE', 'ACTIVE', false, 0, 0, now(), now())"))
                    .hasMessageContaining("account_tenant_revenue_uk");
        }
    }

    private static long single(Statement st, String sql) throws SQLException {
        try (ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }
}
