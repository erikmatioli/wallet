package br.com.walletcore.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Arrays;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** V12 on tenants that existed before it: each gets customers:document once, the other scopes kept. */
class CustomerDocumentScopeMigrationTest {

    private static PostgreSQLContainer postgres;

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

    @Test
    void existingTenantsGetTheDocumentScopeOnce() throws Exception {
        flyway("11").migrate();
        UUID plain = UUID.randomUUID();
        UUID already = UUID.randomUUID();
        try (Connection c = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(),
                postgres.getPassword()); Statement st = c.createStatement()) {
            st.execute("INSERT INTO tenant VALUES ('" + plain + "', 'plain-tenant', 'x', 'Plain', '12345678', '0001',"
                    + " 'ledger:write accounts:read', 'ACTIVE', now())");
            st.execute("INSERT INTO tenant VALUES ('" + already + "', 'already-tenant', 'x', 'Already', '12345679',"
                    + " '0001', 'accounts:read customers:document', 'ACTIVE', now())");
        }

        flyway("12").migrate();

        try (Connection c = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(),
                postgres.getPassword()); Statement st = c.createStatement()) {
            assertThat(scopes(st, plain)).containsExactlyInAnyOrder("ledger:write", "accounts:read", "customers:document");
            assertThat(scopes(st, already)).containsExactlyInAnyOrder("accounts:read", "customers:document");
        }
    }

    private static String[] scopes(Statement st, UUID tenant) throws Exception {
        try (ResultSet rs = st.executeQuery("SELECT scopes FROM tenant WHERE id = '" + tenant + "'")) {
            rs.next();
            return Arrays.stream(rs.getString(1).split(" ")).filter(s -> !s.isBlank()).toArray(String[]::new);
        }
    }
}
