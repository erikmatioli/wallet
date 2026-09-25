package br.com.walletcore.bootstrap.observability;

import br.com.walletcore.application.port.out.MetricsRecorder;
import br.com.walletcore.domain.ledger.TransactionType;
import br.com.walletcore.domain.shared.TenantId;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * Turns business facts reported by the application layer into Micrometer counters, which the
 * Prometheus registry exposes at {@code /actuator/prometheus}. Metric and tag names are the
 * public contract for dashboards and alerts (see {@code docs/observability}); changing them is
 * a breaking change for anyone who built a dashboard on top.
 *
 * <p><b>Cardinality note:</b> {@code tenant} is tagged by {@code client_id} (bounded by the
 * number of white-label clients, expected to stay in the tens/low hundreds). If the platform
 * ever onboards thousands of tenants, drop the per-tenant tag from the hot-path counters below
 * and rely on structured logs (which already carry {@code tenant_id}, see
 * {@code TenantLoggingInterceptor}) for per-tenant drill-down instead.
 */
@Component
public class MicrometerMetricsRecorder implements MetricsRecorder {

    private final MeterRegistry registry;

    public MicrometerMetricsRecorder(MeterRegistry registry) {
        this.registry = registry;
    }

    @Override
    public void transactionPosted(TenantId tenantId, TransactionType type, boolean replayed) {
        Counter.builder("wallet.transactions.total")
                .description("Money movements posted, by type and whether they were an idempotent replay")
                .tag("tenant", tenantId.toString())
                .tag("type", type.name())
                .tag("replayed", Boolean.toString(replayed))
                .register(registry)
                .increment();
    }

    @Override
    public void transactionRejected(TenantId tenantId, TransactionType type, String reasonCode) {
        Counter.builder("wallet.transactions.rejected.total")
                .description("Money movements rejected before posting, by type and reason")
                .tag("tenant", tenantId.toString())
                .tag("type", type.name())
                .tag("reason", reasonCode)
                .register(registry)
                .increment();
    }

    @Override
    public void customerOnboarded(TenantId tenantId) {
        Counter.builder("wallet.customers.onboarded.total")
                .description("Customers onboarded (payment account opened)")
                .tag("tenant", tenantId.toString())
                .register(registry)
                .increment();
    }

    @Override
    public void auditCompleted(TenantId tenantId, boolean consistent, long findingsCount) {
        Counter.builder("wallet.audit.runs.total")
                .description("Ledger audits (replays) completed, by outcome")
                .tag("tenant", tenantId.toString())
                .tag("result", consistent ? "consistent" : "inconsistent")
                .register(registry)
                .increment();
        if (findingsCount > 0) {
            // A dedicated counter (rather than folding into the line above) is what the
            // "any inconsistency in the last N minutes" alert watches - see docs/observability/alerts.yml.
            Counter.builder("wallet.audit.inconsistencies.total")
                    .description("Individual findings (sequence gaps, balance mismatches) detected by audits")
                    .tag("tenant", tenantId.toString())
                    .register(registry)
                    .increment(findingsCount);
        }
    }
}
