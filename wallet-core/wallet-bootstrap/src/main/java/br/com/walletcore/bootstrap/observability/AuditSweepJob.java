package br.com.walletcore.bootstrap.observability;

import br.com.walletcore.application.port.in.AuditLedgerUseCase;
import br.com.walletcore.application.port.out.AccountRepository;
import br.com.walletcore.application.port.out.TenantRepository;
import br.com.walletcore.domain.shared.AccountId;
import br.com.walletcore.domain.shared.TenantId;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Makes "the ledger is verifiably correct" an ongoing property instead of something only checked
 * when a client happens to call {@code GET /v1/accounts/{id}/audit}. Every run re-replays a
 * bounded, recently-active sample of accounts per tenant through {@link AuditLedgerUseCase}; any
 * inconsistency is logged at ERROR with the account id, and (via that same use case) increments
 * {@code wallet.audit.inconsistencies.total}, which {@code docs/observability/alerts.yml} watches.
 *
 * <p>This is a sample, not a full reconciliation: it favours accounts that just moved money,
 * which is where a concurrency or migration bug would show up first. A full nightly sweep of
 * every account is a reasonable next step once the accounts table is large enough that partitioning
 * or a dedicated batch job makes more sense than a fixed-size sample on a scheduler.
 */
@Component
@EnableConfigurationProperties(AuditSweepProperties.class)
class AuditSweepJob {

    private static final Logger log = LoggerFactory.getLogger(AuditSweepJob.class);

    private final TenantRepository tenants;
    private final AccountRepository accounts;
    private final AuditLedgerUseCase auditLedger;
    private final AuditSweepProperties properties;
    private final MeterRegistry registry;
    private final AtomicLong lastSweepEpochSeconds = new AtomicLong();

    AuditSweepJob(TenantRepository tenants, AccountRepository accounts, AuditLedgerUseCase auditLedger,
                  AuditSweepProperties properties, MeterRegistry registry) {
        this.tenants = tenants;
        this.accounts = accounts;
        this.auditLedger = auditLedger;
        this.properties = properties;
        this.registry = registry;
        // A staleness alert ("the sweep itself stopped running") is only possible if the gauge
        // exists from startup, even before the first successful run.
        registry.gauge("wallet.audit.sweep.last_success_epoch_seconds", lastSweepEpochSeconds);
    }

    @Scheduled(fixedDelayString = "${wallet.observability.audit-sweep.interval-ms:300000}",
            initialDelayString = "${wallet.observability.audit-sweep.initial-delay-ms:60000}")
    void sweep() {
        if (!properties.enabled()) {
            return;
        }
        Timer.Sample sample = Timer.start(registry);
        int tenantCount = 0;
        int accountCount = 0;
        try {
            List<TenantId> activeTenants = tenants.findAllActiveIds();
            tenantCount = activeTenants.size();
            for (TenantId tenantId : activeTenants) {
                accountCount += sweepTenant(tenantId);
            }
            lastSweepEpochSeconds.set(Instant.now().getEpochSecond());
        } catch (RuntimeException e) {
            // Never let a sweep failure crash the scheduler thread; the next run tries again,
            // and the gauge above going stale is itself an alertable signal.
            log.error("audit sweep failed: {}", e.toString(), e);
        } finally {
            sample.stop(Timer.builder("wallet.audit.sweep.duration")
                    .description("Wall time of one full audit sweep across all tenants")
                    .register(registry));
            log.info("audit sweep finished: tenants={} accounts={}", tenantCount, accountCount);
        }
    }

    private int sweepTenant(TenantId tenantId) {
        List<AccountId> candidates = accounts.findRecentlyActiveCustomerAccountIds(tenantId, properties.accountsPerTenant());
        for (AccountId accountId : candidates) {
            AuditLedgerUseCase.AuditReport report = auditLedger.audit(tenantId, accountId);
            if (!report.consistent()) {
                log.error("AUDIT INCONSISTENCY tenant={} account={} findings={}", tenantId, accountId,
                        report.findings());
            }
        }
        return candidates.size();
    }
}
