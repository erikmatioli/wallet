package br.com.walletcore.adapter.out.persistence;

import br.com.walletcore.application.port.out.TransactionRunner;
import br.com.walletcore.domain.shared.TenantId;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Runs each unit of work in its own transaction, scoped to a tenant through the transaction-local
 * setting {@code app.tenant_id} (read by the Row Level Security policies), with a bounded lock
 * wait and automatic retry of transient failures (deadlock, lock timeout, connection blips).
 *
 * <p>Writes use READ COMMITTED: balance changes are single atomic {@code UPDATE ... WHERE}
 * statements, which PostgreSQL serialises per row and re-evaluates against the latest committed
 * version, so no stronger isolation level is needed. Reads use a REPEATABLE READ read-only
 * snapshot so that multi-statement reads (e.g. the audit replay) are consistent.
 *
 * <p>Every call is wrapped in a Micrometer {@link Observation}, named {@code wallet.db.transaction}.
 * One instrumentation point gives both a span (child of the current HTTP/trace context, so it
 * shows up nested under the request that triggered it) and a timer/counter
 * ({@code wallet.db.transaction.*} in {@code /actuator/prometheus}) - this is what lets a slow
 * request be traced down to "which database transaction took the time" without extra wiring.
 */
@Component
class JdbcTransactionRunner implements TransactionRunner {

    private static final Logger log = LoggerFactory.getLogger(JdbcTransactionRunner.class);
    private static final int MAX_ATTEMPTS = 4;
    private static final long BASE_BACKOFF_MS = 25;

    private final TransactionTemplate writeTemplate;
    private final TransactionTemplate readTemplate;
    private final JdbcClient jdbc;
    private final ObservationRegistry observations;

    JdbcTransactionRunner(PlatformTransactionManager transactionManager, JdbcClient jdbc,
                          ObservationRegistry observations) {
        this.jdbc = jdbc;
        this.observations = observations;
        this.writeTemplate = new TransactionTemplate(transactionManager);
        this.writeTemplate.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        this.writeTemplate.setTimeout(10);
        this.readTemplate = new TransactionTemplate(transactionManager);
        this.readTemplate.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        this.readTemplate.setReadOnly(true);
        this.readTemplate.setTimeout(30);
    }

    @Override
    public <T> T inTransaction(TenantId tenantId, Supplier<T> work) {
        return observed("write", tenantId, () -> withRetry(() -> writeTemplate.execute(status -> {
            bindTenant(tenantId);
            return work.get();
        })));
    }

    @Override
    public <T> T readOnly(TenantId tenantId, Supplier<T> work) {
        return observed("read", tenantId, () -> withRetry(() -> readTemplate.execute(status -> {
            bindTenant(tenantId);
            return work.get();
        })));
    }

    private <T> T observed(String kind, TenantId tenantId, Supplier<T> work) {
        Observation observation = Observation.createNotStarted("wallet.db.transaction", observations)
                .lowCardinalityKeyValue("kind", kind)
                .lowCardinalityKeyValue("tenant", tenantId.toString());
        return observation.observe(work::get);
    }

    private void bindTenant(TenantId tenantId) {
        jdbc.sql("SELECT set_config('app.tenant_id', :tenant, true), set_config('lock_timeout', '3s', true)")
                .param("tenant", tenantId.value().toString())
                .query((rs, i) -> rs.getString(1))
                .single();
    }

    private <T> T withRetry(Supplier<T> action) {
        int attempt = 1;
        while (true) {
            try {
                return action.get();
            } catch (TransientDataAccessException e) {
                if (attempt >= MAX_ATTEMPTS) {
                    throw e;
                }
                long backoff = BASE_BACKOFF_MS * (1L << (attempt - 1)) + ThreadLocalRandom.current().nextLong(15);
                log.warn("Transient database failure ({}), retry {}/{} in {} ms", e.getClass().getSimpleName(),
                        attempt, MAX_ATTEMPTS - 1, backoff);
                sleep(backoff);
                attempt++;
            }
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while waiting to retry", ie);
        }
    }
}
