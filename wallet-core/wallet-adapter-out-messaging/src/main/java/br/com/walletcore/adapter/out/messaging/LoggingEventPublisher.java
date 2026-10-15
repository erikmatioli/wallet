package br.com.walletcore.adapter.out.messaging;

import br.com.walletcore.application.port.out.EventPublisher;
import br.com.walletcore.application.port.out.OutboxMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Logs event metadata instead of publishing: the default when {@code wallet.events.publisher} is not {@code sns}
 * (tests, a core running without the bus). See {@link EventPublisherConfig}.
 */
class LoggingEventPublisher implements EventPublisher {

    private static final Logger log = LoggerFactory.getLogger(LoggingEventPublisher.class);

    @Override
    public void publish(OutboxMessage message) {
        log.info("event published: type={} aggregate={}/{} id={} tenant={}", message.eventType(),
                message.aggregateType(), message.aggregateId(), message.id(), message.tenantId());
        log.debug("event payload: {}", message.payload());
    }
}
