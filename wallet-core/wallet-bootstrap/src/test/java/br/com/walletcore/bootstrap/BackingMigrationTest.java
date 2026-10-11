package br.com.walletcore.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * ADR-011's V9 on data written before it: a tenant whose settlement holds a deposit, a Pix and a withdrawal that was
 * reversed. The migration must create the default backing accounts, move the settlement's balance to them by rail in
 * one balanced RECLASSIFICATION, and leave the settlement at zero and closed - without rewriting a single entry.
 */
class BackingMigrationTest {

    private static PostgreSQLContainer postgres;

    private final UUID tenant = UUID.randomUUID();
    private final UUID customer = UUID.randomUUID();
    private final UUID account = UUID.randomUUID();
    private final UUID s1 = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private final UUID s2 = UUID.fromString("00000000-0000-0000-0000-000000000002");

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
            // RLS is forced on the owner too: write and read as the tenant.
            st.execute("SELECT set_config('app.tenant_id', '" + tenant + "', false)");
        }
        return c;
    }

    @Test
    void reclassifiesTheSettlementByRail() throws Exception {
        flyway("8").migrate();
        try (Connection c = connect(); Statement st = c.createStatement()) {
            c.setAutoCommit(false); // the ledger's balance and Pix-detail checks run at commit
            st.execute("INSERT INTO tenant VALUES ('" + tenant + "', 'old-tenant', 'x', 'Old', '12345678', '0001',"
                    + " 'ledger:write ledger:audit', 'ACTIVE', now())");
            st.execute("INSERT INTO customer (id, tenant_id, name, tax_id, tax_id_type, status, created_at) VALUES ('"
                    + customer + "', '" + tenant + "', 'Maria', '52998224725', 'CPF', 'ACTIVE', now())");
            st.execute("INSERT INTO account (id, tenant_id, kind, customer_id, ispb, branch, account_number, check_digit,"
                    + " account_type, status, allow_negative, balance_cents, version, created_at, updated_at) VALUES ('"
                    + account + "', '" + tenant + "', 'CUSTOMER', '" + customer + "', '12345678', '0001', '00100001',"
                    + " '1', 'TRAN', 'ACTIVE', false, 15000, 4, now(), now())");
            for (UUID s : new UUID[] {s1, s2}) {
                st.execute("INSERT INTO account (id, tenant_id, kind, status, allow_negative, balance_cents, version,"
                        + " created_at, updated_at) VALUES ('" + s + "', '" + tenant + "', 'SETTLEMENT', 'ACTIVE', true,"
                        + (s.equals(s1) ? "-7000" : "-8000") + ", 2, now(), now())");
            }
            // deposit 100,00 | Pix in 50,00 | withdrawal 30,00 | its reversal 30,00 (a DEPOSIT keyed 'reversal:')
            UUID w = UUID.randomUUID();
            post(st, UUID.randomUUID(), "DEPOSIT", "d1", 10_000, s1, 1, -10_000, 1, 10_000, true);
            UUID pix = UUID.randomUUID();
            post(st, pix, "PIX_IN", "p1", 5_000, s2, 1, -5_000, 2, 15_000, true);
            st.execute("INSERT INTO pix_transaction_detail (transaction_id, tenant_id, type, end_to_end_id,"
                    + " counterparty_name, counterparty_tax_id_masked, counterparty_ispb, counterparty_account) VALUES ('"
                    + pix + "', '" + tenant + "', 'PIX_IN', 'E9999999920261001120000000000001', 'Joao', '***0001',"
                    + " '99999999', '1')");
            post(st, w, "WITHDRAWAL", "w1", 3_000, s1, 2, -7_000, 3, 12_000, false);
            post(st, UUID.randomUUID(), "DEPOSIT", "reversal:" + w, 3_000, s2, 2, -8_000, 4, 15_000, true);
            c.commit();
        }

        flyway("9").migrate();

        try (Connection c = connect(); Statement st = c.createStatement()) {
            Map<String, Long> expected = new HashMap<>();
            try (ResultSet rs = st.executeQuery("""
                    SELECT b.type, -SUM(a.balance_cents) AS expected, count(*) AS shards
                      FROM backing_account b JOIN account a ON a.backing_account_id = b.id
                     GROUP BY b.type""")) {
                while (rs.next()) {
                    expected.put(rs.getString("type"), rs.getLong("expected"));
                    assertThat(rs.getInt("shards")).as("shards of %s", rs.getString("type")).isEqualTo(2);
                }
            }
            assertThat(expected).containsExactlyInAnyOrderEntriesOf(Map.of(
                    "PIX_PI", 5_000L, "CASH", 0L, "UNASSIGNED", 10_000L));
            assertThat(single(st, "SELECT count(*) FROM account WHERE kind = 'SETTLEMENT' AND balance_cents = 0"
                    + " AND status = 'CLOSED'")).isEqualTo(2);
            assertThat(single(st, "SELECT SUM(balance_cents) FROM account")).isZero(); // still double entry
            assertThat(single(st, "SELECT amount_cents FROM financial_transaction WHERE type = 'RECLASSIFICATION'"))
                    .isEqualTo(15_000);
            assertThat(single(st, "SELECT count(*) FROM ledger_entry WHERE type = 'RECLASSIFICATION'")).isEqualTo(4);
            // Every account's sequence still has no gap and its last balance matches the projection.
            assertThat(single(st, """
                    SELECT count(*) FROM account a
                     WHERE a.version <> (SELECT count(*) FROM ledger_entry e WHERE e.account_id = a.id)
                        OR a.balance_cents <> COALESCE((SELECT e.balance_after_cents FROM ledger_entry e
                                                         WHERE e.account_id = a.id ORDER BY e.sequence_no DESC LIMIT 1), 0)
                    """)).isZero();
            try (ResultSet rs = st.executeQuery("SELECT scopes FROM tenant")) {
                rs.next();
                assertThat(rs.getString(1)).contains("treasury:write");
            }
        }
    }

    /** A two-leg transaction: the settlement shard and the customer account, with their new sequence and balance. */
    private void post(Statement st, UUID tx, String type, String key, long cents, UUID shard, long shardSeq,
                      long shardBalance, long accountSeq, long accountBalance, boolean creditsCustomer)
            throws SQLException {
        st.execute("INSERT INTO financial_transaction (id, tenant_id, idempotency_key, fingerprint, type, amount_cents,"
                + " occurred_at) VALUES ('" + tx + "', '" + tenant + "', '" + key + "', 'fp', '" + type + "', " + cents
                + ", now())");
        st.execute("INSERT INTO ledger_entry (id, tenant_id, transaction_id, account_id, sequence_no, direction,"
                + " amount_cents, balance_after_cents, type, counterparty_account_id, occurred_at) VALUES ('"
                + UUID.randomUUID() + "', '" + tenant + "', '" + tx + "', '" + shard + "', " + shardSeq + ", '"
                + (creditsCustomer ? "DEBIT" : "CREDIT") + "', " + cents + ", " + shardBalance + ", '" + type + "', '"
                + account + "', now())");
        st.execute("INSERT INTO ledger_entry (id, tenant_id, transaction_id, account_id, sequence_no, direction,"
                + " amount_cents, balance_after_cents, type, counterparty_account_id, occurred_at) VALUES ('"
                + UUID.randomUUID() + "', '" + tenant + "', '" + tx + "', '" + account + "', " + accountSeq + ", '"
                + (creditsCustomer ? "CREDIT" : "DEBIT") + "', " + cents + ", " + accountBalance + ", '" + type + "', '"
                + shard + "', now())");
    }

    private static long single(Statement st, String sql) throws SQLException {
        try (ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }
}
