package br.com.walletcore.adapter.out.persistence;

import br.com.walletcore.application.port.out.EventPublisher;
import br.com.walletcore.application.port.out.OutboxMessage;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Polls the transactional outbox and hands events to the {@link EventPublisher} port.
 * Rows are claimed with FOR UPDATE SKIP LOCKED, so several instances can run in parallel.
 * Delivery is at-least-once (a crash between publish and commit re-delivers); consumers
 * de-duplicate on the event id. A failing publisher rolls the batch back and it is retried.
 *
 * <p>Limitation to be aware of: with several relay instances, ordering across instances is not
 * guaranteed. If strict per-aggregate ordering is required, run a single relay (leader) or
 * partition by aggregate id.
 */
@Component
class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);
    /** Same key as TenantLoggingInterceptor (inbound adapter, which this module must not depend on). */
    private static final String TENANT_MDC_KEY = "tenant_id";

    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final EventPublisher publisher;
    private final int batchSize;
    private final AtomicLong pendingCount = new AtomicLong();

    OutboxRelay(JdbcClient jdbc, PlatformTransactionManager transactionManager, EventPublisher publisher,
                MeterRegistry registry, @Value("${wallet.outbox.batch-size:100}") int batchSize) {
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(transactionManager);
        this.publisher = publisher;
        this.batchSize = batchSize;
        // A growing backlog (events created faster than they are published) is exactly the
        // "something downstream is broken" signal alerts.yml's WalletOutboxBacklogGrowing rule
        // watches for - registered eagerly so the gauge exists (as zero) from application startup.
        registry.gauge("wallet.outbox.pending", pendingCount);
    }

    @Scheduled(fixedDelayString = "${wallet.outbox.poll-interval-ms:500}")
    void relay() {
        try {
            Integer published;
            do {
                published = tx.execute(status -> publishBatch());
            } while (published != null && published == batchSize);
            refreshPendingCount();
        } catch (RuntimeException e) {
            log.warn("Outbox relay failed, will retry on next tick: {}", e.toString());
        }
    }

    private int publishBatch() {
        List<OutboxMessage> batch = jdbc.sql("""
                SELECT id, tenant_id, aggregate_type, aggregate_id, event_type, CAST(payload AS text) AS payload, created_at
                  FROM outbox_event
                 WHERE published_at IS NULL
                 ORDER BY seq
                 LIMIT :limit
                 FOR UPDATE SKIP LOCKED""")
                .param("limit", batchSize)
                .query((rs, i) -> new OutboxMessage(
                        Sql.uuid(rs, "id"), Sql.uuid(rs, "tenant_id"), rs.getString("aggregate_type"),
                        rs.getString("aggregate_id"), rs.getString("event_type"), rs.getString("payload"),
                        Sql.instant(rs, "created_at")))
                .list();
        for (OutboxMessage message : batch) {
            // The relay runs on a scheduler thread, outside any request: put the event's own
            // tenant in the MDC so what the publisher logs carries tenant_id like request logs do.
            try (MDC.MDCCloseable ignored = MDC.putCloseable(TENANT_MDC_KEY, message.tenantId().toString())) {
                publisher.publish(message);
            }
            jdbc.sql("UPDATE outbox_event SET published_at = now() WHERE id = :id")
                    .param("id", message.id())
                    .update();
        }
        return batch.size();
    }

    private void refreshPendingCount() {
        Long count = jdbc.sql("SELECT count(*) FROM outbox_event WHERE published_at IS NULL")
                .query(Long.class)
                .single();
        pendingCount.set(count);
    }
}
