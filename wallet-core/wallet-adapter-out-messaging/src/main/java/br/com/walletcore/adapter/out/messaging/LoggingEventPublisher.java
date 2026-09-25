package br.com.walletcore.adapter.out.messaging;

import br.com.walletcore.application.port.out.EventPublisher;
import br.com.walletcore.application.port.out.OutboxMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Default publisher: logs event metadata. Replace it with a Kafka / SNS / SQS adapter by
 * providing another {@link EventPublisher} implementation in this module (use the aggregate id
 * as the partition key to keep per-aggregate ordering, and the event id as the dedup key).
 */
@Component
class LoggingEventPublisher implements EventPublisher {

    private static final Logger log = LoggerFactory.getLogger(LoggingEventPublisher.class);

    @Override
    public void publish(OutboxMessage message) {
        log.info("event published: type={} aggregate={}/{} id={} tenant={}", message.eventType(),
                message.aggregateType(), message.aggregateId(), message.id(), message.tenantId());
        log.debug("event payload: {}", message.payload());
    }
}
