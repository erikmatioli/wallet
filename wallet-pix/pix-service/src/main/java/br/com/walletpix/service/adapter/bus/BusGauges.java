package br.com.walletpix.service.adapter.bus;

import br.com.walletpix.service.adapter.out.persistence.JdbcOutbox;
import br.com.walletpix.service.config.PixProperties;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.QueueAttributeName;

/**
 * Backlog gauges, sampled every 15s:
 * <ul>
 *   <li>{@code pix_outbox_pending}: messages committed but not yet on SNS. Growing = the relay or
 *       SNS is down, and Pix are waiting to leave.</li>
 *   <li>{@code pix_queue_messages{queue,state}}: the SPI inbound queue and its DLQ (found through
 *       the queue's RedrivePolicy, so no extra configuration). Anything in the DLQ is a message
 *       that failed for good - every one of them needs a human.</li>
 * </ul>
 * A sampling failure keeps the last value and is logged; it never affects message processing.
 */
@Component
class BusGauges {

    private static final Logger log = LoggerFactory.getLogger(BusGauges.class);

    private final JdbcOutbox outbox;
    private final SqsClient sqs;
    private final MeterRegistry registry;
    private final String inboundQueue;
    private final AtomicLong outboxPending = new AtomicLong();
    private final Map<String, AtomicLong> queueValues = new ConcurrentHashMap<>();
    private volatile String dlqName;

    BusGauges(JdbcOutbox outbox, SqsClient sqs, MeterRegistry registry, PixProperties props) {
        this.outbox = outbox;
        this.sqs = sqs;
        this.registry = registry;
        this.inboundQueue = props.bus().spiInboundQueue();
        Gauge.builder("pix.outbox.pending", outboxPending, AtomicLong::get)
                .description("Outbox messages not yet published to SNS")
                .register(registry);
    }

    @Scheduled(fixedDelay = 15_000, initialDelay = 5_000)
    void sample() {
        try {
            outboxPending.set(outbox.countPending());
        } catch (RuntimeException e) {
            log.debug("outbox gauge sampling failed: {}", e.toString());
        }
        try {
            String inboundUrl = sqs.getQueueUrl(b -> b.queueName(inboundQueue)).queueUrl();
            Map<QueueAttributeName, String> attrs = sqs.getQueueAttributes(b -> b.queueUrl(inboundUrl).attributeNames(
                    QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES,
                    QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES_NOT_VISIBLE,
                    QueueAttributeName.REDRIVE_POLICY)).attributes();
            record(inboundQueue, attrs);
            dlqName(attrs).ifPresent(dlq -> {
                String dlqUrl = sqs.getQueueUrl(b -> b.queueName(dlq)).queueUrl();
                record(dlq, sqs.getQueueAttributes(b -> b.queueUrl(dlqUrl).attributeNames(
                        QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES,
                        QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES_NOT_VISIBLE)).attributes());
            });
        } catch (RuntimeException e) {
            log.debug("queue gauge sampling failed: {}", e.toString());
        }
    }

    private void record(String queue, Map<QueueAttributeName, String> attrs) {
        value(queue, "visible").set(parse(attrs.get(QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES)));
        value(queue, "in_flight").set(parse(attrs.get(QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES_NOT_VISIBLE)));
    }

    private AtomicLong value(String queue, String state) {
        return queueValues.computeIfAbsent(queue + "|" + state, k -> {
            AtomicLong holder = new AtomicLong();
            Gauge.builder("pix.queue.messages", holder, AtomicLong::get)
                    .description("Approximate messages in an SQS queue, by state")
                    .tag("queue", queue)
                    .tag("state", state)
                    .register(registry);
            return holder;
        });
    }

    /** {"deadLetterTargetArn":"arn:aws:sqs:us-east-1:000000000000:wallet-pix-spi-inbound-dlq",...} -> queue name. */
    private Optional<String> dlqName(Map<QueueAttributeName, String> attrs) {
        if (dlqName == null) {
            String policy = attrs.get(QueueAttributeName.REDRIVE_POLICY);
            if (policy != null) {
                var m = java.util.regex.Pattern.compile("arn:aws:sqs:[^:]+:[^:]+:([^\"]+)").matcher(policy);
                if (m.find()) {
                    dlqName = m.group(1);
                }
            }
        }
        return Optional.ofNullable(dlqName);
    }

    private static long parse(String value) {
        return value == null ? 0 : Long.parseLong(value);
    }
}
