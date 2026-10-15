package br.com.walletcore.adapter.out.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.walletcore.application.port.out.OutboxMessage;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sns.model.PublishRequest;
import software.amazon.awssdk.services.sns.model.PublishResponse;

/** ADR-014: each aggregate goes to its topic, with the attributes a queue can filter on. */
class SnsEventPublisherTest {

    private static final String TRANSACTIONS = "arn:aws:sns:us-east-1:000000000000:wallet-core-transaction-events";
    private static final String ACCOUNTS = "arn:aws:sns:us-east-1:000000000000:wallet-core-account-events";

    private final List<PublishRequest> published = new ArrayList<>();
    private final SnsClient sns = new SnsClient() {
        @Override
        public PublishResponse publish(PublishRequest request) {
            published.add(request);
            return PublishResponse.builder().messageId("m-" + published.size()).build();
        }

        @Override
        public String serviceName() {
            return "sns";
        }

        @Override
        public void close() {
        }
    };
    private final SnsEventPublisher publisher = new SnsEventPublisher(sns, TRANSACTIONS, ACCOUNTS);

    private OutboxMessage message(String aggregateType, String eventType) {
        return new OutboxMessage(UUID.randomUUID(), UUID.randomUUID(), aggregateType, "agg-1", eventType,
                "{\"x\":1}", Instant.now());
    }

    @Test
    void transactionsGoToTheirTopicAndAccountsAndCustomersToTheOther() {
        OutboxMessage posted = message("transaction", "wallet.transaction.posted.v1");
        publisher.publish(posted);
        publisher.publish(message("customer", "wallet.customer.onboarded.v1"));
        publisher.publish(message("account", "wallet.account.blocked.v1"));

        assertThat(published).extracting(PublishRequest::topicArn).containsExactly(TRANSACTIONS, ACCOUNTS, ACCOUNTS);
        PublishRequest first = published.getFirst();
        assertThat(first.message()).isEqualTo("{\"x\":1}");
        assertThat(first.messageAttributes().get(SnsEventPublisher.EVENT_ID).stringValue())
                .isEqualTo(posted.id().toString());
        assertThat(first.messageAttributes().get(SnsEventPublisher.EVENT_TYPE).stringValue())
                .isEqualTo("wallet.transaction.posted.v1");
        assertThat(first.messageAttributes().get(SnsEventPublisher.TENANT_ID).stringValue())
                .isEqualTo(posted.tenantId().toString());
    }

    @Test
    void anAggregateWithoutATopicStaysInTheOutbox() {
        assertThatThrownBy(() -> publisher.publish(message("schedule", "x"))).isInstanceOf(IllegalStateException.class);
        assertThat(published).isEmpty();
    }
}
