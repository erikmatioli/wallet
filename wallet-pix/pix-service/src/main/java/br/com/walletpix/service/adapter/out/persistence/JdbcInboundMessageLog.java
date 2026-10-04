package br.com.walletpix.service.adapter.out.persistence;

import br.com.walletpix.service.application.port.PixPorts.InboundMessageLog;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcInboundMessageLog implements InboundMessageLog {

    private final JdbcClient jdbc;

    JdbcInboundMessageLog(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean alreadyProcessed(String messageKey) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM inbound_message WHERE message_key = :key)")
                .param("key", messageKey)
                .query(Boolean.class)
                .single();
    }

    /**
     * ON CONFLICT DO NOTHING instead of catching a duplicate-key error: an error would abort the
     * surrounding PostgreSQL transaction, while this just reports "someone else was first".
     * A concurrent insert of the same key blocks on the unique index until the other
     * transaction ends, so the loser reliably sees 0 rows.
     */
    @Override
    public boolean markProcessed(String messageKey, String messageType) {
        return jdbc.sql("""
                INSERT INTO inbound_message (message_key, message_type, processed_at)
                VALUES (:key, :type, now())
                ON CONFLICT (message_key) DO NOTHING""")
                .param("key", messageKey)
                .param("type", messageType)
                .update() == 1;
    }
}
