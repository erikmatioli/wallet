package br.com.walletpix.service.adapter.out.persistence;

import br.com.walletpix.messages.PixEvents.PixEvent;
import br.com.walletpix.messages.SpiMessages.Envelope;
import br.com.walletpix.service.adapter.bus.TraceContext;
import br.com.walletpix.service.application.port.PixPorts.OutboundMessages;
import java.sql.Types;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;

/**
 * Writes outgoing messages to the outbox table, in the caller's transaction. {@link OutboxRelay}
 * publishes them to SNS afterwards: a message exists if and only if the state change that
 * produced it committed.
 */
@Repository
public class JdbcOutbox implements OutboundMessages {

    public enum Destination { SPI, EVENTS }

    public record Pending(long id, Destination destination, String messageType, String payload, String traceparent) {
    }

    private final JdbcClient jdbc;
    private final JsonMapper json;

    JdbcOutbox(JdbcClient jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Override
    public void sendToSpi(Envelope<?> message) {
        enqueue(Destination.SPI, message.appHdr().msgDefIdr(), json.writeValueAsString(message));
    }

    @Override
    public void publishEvent(PixEvent event) {
        enqueue(Destination.EVENTS, event.type().name(), json.writeValueAsString(event));
    }

    private void enqueue(Destination destination, String messageType, String payload) {
        jdbc.sql("""
                INSERT INTO outbox (destination, message_type, payload, traceparent, created_at)
                VALUES (:destination, :type, :payload, :traceparent, now())""")
                .param("destination", destination.name())
                .param("type", messageType)
                .param("payload", payload)
                .param("traceparent", TraceContext.currentTraceparent(), Types.VARCHAR)
                .update();
    }

    /** Oldest unpublished rows, locked so that several instances never publish the same row. */
    public List<Pending> lockPending(int limit) {
        return jdbc.sql("""
                SELECT id, destination, message_type, payload, traceparent
                  FROM outbox
                 WHERE published_at IS NULL
                 ORDER BY id
                 LIMIT :limit
                   FOR UPDATE SKIP LOCKED""")
                .param("limit", limit)
                .query((rs, i) -> new Pending(rs.getLong("id"), Destination.valueOf(rs.getString("destination")),
                        rs.getString("message_type"), rs.getString("payload"), rs.getString("traceparent")))
                .list();
    }

    /** Rows not yet on SNS; served by the partial index outbox_pending_idx. */
    public long countPending() {
        return jdbc.sql("SELECT count(*) FROM outbox WHERE published_at IS NULL").query(Long.class).single();
    }

    public void markPublished(long id) {
        jdbc.sql("UPDATE outbox SET published_at = now() WHERE id = :id").param("id", id).update();
    }
}
