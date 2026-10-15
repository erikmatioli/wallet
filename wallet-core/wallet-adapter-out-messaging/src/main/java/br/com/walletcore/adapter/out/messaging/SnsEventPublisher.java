package br.com.walletcore.adapter.out.messaging;

import br.com.walletcore.application.port.out.EventPublisher;
import br.com.walletcore.application.port.out.OutboxMessage;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sns.model.MessageAttributeValue;

/**
 * Publishes the outbox to SNS (ADR-014): the transactions to one topic, the customers and accounts to another, each
 * consumer with its own SQS queue. At least once: the relay marks a row published only after SNS took it, and a
 * consumer de-duplicates on {@code eventId}. The attributes let a queue filter without reading the body.
 */
class SnsEventPublisher implements EventPublisher {

    static final String EVENT_ID = "eventId";
    static final String EVENT_TYPE = "eventType";
    static final String TENANT_ID = "tenantId";

    private static final Logger log = LoggerFactory.getLogger(SnsEventPublisher.class);

    private final SnsClient sns;
    private final String transactionTopicArn;
    private final String accountTopicArn;

    SnsEventPublisher(SnsClient sns, String transactionTopicArn, String accountTopicArn) {
        this.sns = sns;
        this.transactionTopicArn = transactionTopicArn;
        this.accountTopicArn = accountTopicArn;
    }

    @Override
    public void publish(OutboxMessage message) {
        String topic = topicOf(message.aggregateType());
        sns.publish(b -> b.topicArn(topic).message(message.payload()).messageAttributes(Map.of(
                EVENT_ID, text(message.id().toString()),
                EVENT_TYPE, text(message.eventType()),
                TENANT_ID, text(message.tenantId().toString()))));
        log.debug("event published to SNS: type={} id={}", message.eventType(), message.id());
    }

    String topicOf(String aggregateType) {
        return switch (aggregateType) {
            case "transaction" -> transactionTopicArn;
            case "customer", "account" -> accountTopicArn;
            // A new aggregate with no topic is a bug: failing keeps the row in the outbox until it is mapped.
            default -> throw new IllegalStateException("no SNS topic for aggregate type " + aggregateType);
        };
    }

    private static MessageAttributeValue text(String value) {
        return MessageAttributeValue.builder().dataType("String").stringValue(value).build();
    }
}
