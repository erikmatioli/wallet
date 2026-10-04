package br.com.walletpix.service.adapter.bus;

import br.com.walletpix.messages.PixEvents;
import br.com.walletpix.service.adapter.out.persistence.JdbcOutbox;
import br.com.walletpix.service.adapter.out.persistence.JdbcOutbox.Pending;
import br.com.walletpix.service.config.PixProperties;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sns.model.MessageAttributeValue;

/**
 * Publishes outbox rows to SNS, at least once: a row is marked published only after SNS
 * accepted it, so a crash in between republishes it - receivers deduplicate on the message id.
 * Rows are locked with SKIP LOCKED, so more than one instance can run this safely.
 */
@Component
class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);
    private static final int BATCH = 50;

    private final JdbcOutbox outbox;
    private final SnsClient sns;
    private final TransactionTemplate tx;
    private final PixProperties.Bus bus;

    OutboxRelay(JdbcOutbox outbox, SnsClient sns, TransactionTemplate tx, PixProperties props) {
        this.outbox = outbox;
        this.sns = sns;
        this.tx = tx;
        this.bus = props.bus();
    }

    @Scheduled(fixedDelayString = "${pix.bus.outbox-poll-interval-ms:200}")
    void relay() {
        try {
            Integer published;
            do {
                published = tx.execute(status -> publishBatch());
            } while (published != null && published == BATCH);
        } catch (RuntimeException e) {
            // Nothing was marked published for the failed batch: it goes out on the next run.
            log.warn("Outbox relay failed, will retry: {}", e.toString());
        }
    }

    private int publishBatch() {
        List<Pending> batch = outbox.lockPending(BATCH);
        for (Pending row : batch) {
            Map<String, MessageAttributeValue> attributes = new HashMap<>();
            String topic;
            if (row.destination() == JdbcOutbox.Destination.SPI) {
                topic = bus.pspToSpiTopicArn();
                attributes.put("msgType", text(row.messageType()));
            } else {
                topic = bus.paymentEventsTopicArn();
                attributes.put(PixEvents.TYPE_ATTRIBUTE, text(row.messageType()));
            }
            if (row.traceparent() != null) {
                attributes.put(TraceContext.ATTRIBUTE, text(row.traceparent()));
            }
            sns.publish(b -> b.topicArn(topic).message(row.payload()).messageAttributes(attributes));
            outbox.markPublished(row.id());
        }
        return batch.size();
    }

    private static MessageAttributeValue text(String value) {
        return MessageAttributeValue.builder().dataType("String").stringValue(value).build();
    }
}
