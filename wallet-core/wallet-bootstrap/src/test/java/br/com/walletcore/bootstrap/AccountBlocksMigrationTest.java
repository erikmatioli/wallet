package br.com.walletcore.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * ADR-013's V10 on data written before it: the accounts that already exist get their opening in the CCS feed, at the
 * date they were created and in that order; the tenant gets its judicial transit and the new scopes. And the block
 * table takes only its release, once.
 */
class AccountBlocksMigrationTest {

    private static PostgreSQLContainer postgres;

    private final UUID tenant = UUID.randomUUID();
    private final UUID older = UUID.randomUUID();
    private final UUID newer = UUID.randomUUID();

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

    /** Each test migrates its own database: one stops at V9 before writing, the other goes straight to V10. */
    private String url;

    @BeforeEach
    void freshDatabase() throws SQLException {
        String name = "t" + UUID.randomUUID().toString().replace("-", "");
        try (Connection c = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(),
                postgres.getPassword()); Statement st = c.createStatement()) {
            st.execute("CREATE DATABASE " + name);
        }
        url = postgres.getJdbcUrl().replaceFirst("/[^/?]+(\\?|$)","/" + name + "$1");
    }

    private Flyway flyway(String target) {
        return Flyway.configure()
                .dataSource(url, postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/migration")
                .target(target)
                .load();
    }

    private Connection connect() throws SQLException {
        Connection c = DriverManager.getConnection(url, postgres.getUsername(), postgres.getPassword());
        try (Statement st = c.createStatement()) {
            // RLS is forced on the owner too: write and read as the tenant.
            st.execute("SELECT set_config('app.tenant_id', '" + tenant + "', false)");
        }
        return c;
    }

    @Test
    void theExistingAccountsOpenInTheFeedInTheOrderTheyWereCreated() throws Exception {
        flyway("9").migrate();
        try (Connection c = connect(); Statement st = c.createStatement()) {
            st.execute("INSERT INTO tenant VALUES ('" + tenant + "', 'old-tenant', 'x', 'Old', '12345678', '0001',"
                    + " 'ledger:write accounts:read', 'ACTIVE', now())");
            // Inserted newest first: the feed must still follow the creation dates.
            account(st, newer, "11144477735", "00100002", "2026-10-05T10:00:00Z");
            account(st, older, "52998224725", "00100001", "2026-01-15T09:30:00Z");
        }

        flyway("10").migrate();

        try (Connection c = connect(); Statement st = c.createStatement()) {
            List<String> feed = new ArrayList<>();
            try (ResultSet rs = st.executeQuery("""
                    SELECT account_id, from_status, to_status, occurred_at, operator, client_id
                      FROM account_status_event ORDER BY seq""")) {
                while (rs.next()) {
                    assertThat(rs.getString("from_status")).isNull();
                    assertThat(rs.getString("to_status")).isEqualTo("ACTIVE");
                    assertThat(rs.getString("client_id")).isEqualTo("old-tenant");
                    feed.add(rs.getString("account_id"));
                }
            }
            assertThat(feed).containsExactly(older.toString(), newer.toString());
            assertThat(single(st, "SELECT count(*) FROM account WHERE kind = 'JUDICIAL_TRANSIT' AND NOT allow_negative"))
                    .isEqualTo(1);
            assertThat(single(st, "SELECT count(*) FROM account WHERE kind = 'CUSTOMER'"
                    + " AND credit_policy = 'ALLOWED' AND debit_policy = 'ALLOWED' AND closed_at IS NULL")).isEqualTo(2);
            try (ResultSet rs = st.executeQuery("SELECT scopes FROM tenant")) {
                rs.next();
                assertThat(rs.getString(1).split(" ")).contains("accounts:block", "accounts:judicial", "accounts:close",
                        "ccs:read");
            }
            assertThatThrownBy(() -> st.execute("UPDATE account SET status = 'BLOCKED' WHERE id = '" + older + "'"))
                    .hasMessageContaining("account_status_check");
        }
    }

    @Test
    void aBlockTakesOnlyItsReleaseAndOnlyOnce() throws Exception {
        flyway("10").migrate();
        UUID block = UUID.randomUUID();
        UUID account = UUID.randomUUID();
        try (Connection c = connect(); Statement st = c.createStatement()) {
            st.execute("INSERT INTO tenant VALUES ('" + tenant + "', 't-" + tenant.toString().substring(0, 8)
                    + "', 'x', 'T', '12345678', '0001', '', 'ACTIVE', now())");
            account(st, account, "39053344705", "00100003", "2026-10-01T10:00:00Z");
            st.execute("INSERT INTO account_block (id, tenant_id, account_id, type, reason, court_process,"
                    + " created_operator, created_client, created_at) VALUES ('" + block + "', '" + tenant + "', '"
                    + account + "', 'JUDICIAL', 'COURT_ORDER', '0001234-56.2026.8.26.0100', 'maria', 't', now())");

            assertThatThrownBy(() -> st.execute("UPDATE account_block SET type = 'FRAUD', reason = 'FRAUD_SUSPICION'"
                    + " WHERE id = '" + block + "'")).hasMessageContaining("only takes its release");
            st.execute("UPDATE account_block SET released_at = now(), released_operator = 'joao',"
                    + " released_client = 't', release_note = 'ordem cumprida' WHERE id = '" + block + "'");
            assertThatThrownBy(() -> st.execute("UPDATE account_block SET release_note = 'outra' WHERE id = '" + block
                    + "'")).hasMessageContaining("already released");
            assertThatThrownBy(() -> st.execute("DELETE FROM account_block WHERE id = '" + block + "'"))
                    .isInstanceOf(SQLException.class);
            assertThatThrownBy(() -> st.execute("INSERT INTO account_block (id, tenant_id, account_id, type, reason,"
                    + " created_operator, created_client, created_at) VALUES ('" + UUID.randomUUID() + "', '" + tenant
                    + "', '" + account + "', 'JUDICIAL', 'COURT_ORDER', 'maria', 't', now())"))
                    .hasMessageContaining("account_block_judicial");
        }
    }

    private void account(Statement st, UUID id, String taxId, String number, String createdAt) throws SQLException {
        UUID customer = UUID.randomUUID();
        st.execute("INSERT INTO customer (id, tenant_id, name, tax_id, tax_id_type, status, created_at) VALUES ('"
                + customer + "', '" + tenant + "', 'Cliente', '" + taxId + "', 'CPF', 'ACTIVE', '" + createdAt + "')");
        st.execute("INSERT INTO account (id, tenant_id, kind, customer_id, ispb, branch, account_number, check_digit,"
                + " account_type, status, allow_negative, balance_cents, version, created_at, updated_at) VALUES ('"
                + id + "', '" + tenant + "', 'CUSTOMER', '" + customer + "', '12345678', '0001', '" + number + "',"
                + " '1', 'TRAN', 'ACTIVE', false, 0, 0, '" + createdAt + "', '" + createdAt + "')");
    }

    private static long single(Statement st, String sql) throws SQLException {
        try (ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }
}
