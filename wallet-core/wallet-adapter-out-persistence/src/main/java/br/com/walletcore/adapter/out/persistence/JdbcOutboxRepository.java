package br.com.walletcore.adapter.out.persistence;

import br.com.walletcore.application.port.out.OutboxRepository;
import br.com.walletcore.domain.event.DomainEvent;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;

@Repository
class JdbcOutboxRepository implements OutboxRepository {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final JdbcClient jdbc;

    JdbcOutboxRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void enqueue(List<? extends DomainEvent> events) {
        for (DomainEvent event : events) {
            jdbc.sql("""
                    INSERT INTO outbox_event (id, tenant_id, aggregate_type, aggregate_id, event_type, payload, created_at)
                    VALUES (:id, :tenant, :aggregateType, :aggregateId, :eventType, CAST(:payload AS jsonb), :createdAt)""")
                    .param("id", event.eventId())
                    .param("tenant", event.tenantId())
                    .param("aggregateType", event.aggregateType())
                    .param("aggregateId", event.aggregateId())
                    .param("eventType", event.eventType())
                    .param("payload", JSON.writeValueAsString(event))
                    .param("createdAt", Sql.ts(event.occurredAt()))
                    .update();
        }
    }
}
