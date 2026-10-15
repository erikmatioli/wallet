package br.com.walletcore.adapter.out.messaging;

import br.com.walletcore.application.port.out.EventPublisher;
import java.net.URI;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sns.SnsClient;

/**
 * Which publisher the outbox relay uses: {@code wallet.events.publisher=sns} publishes to SNS (ADR-014); anything
 * else, or nothing, keeps the log, as tests and a core running alone need. With {@code wallet.events.endpoint} set
 * (LocalStack), the client uses it and the static keys given; without it, the AWS endpoint and credential chain.
 */
@Configuration(proxyBeanMethods = false)
class EventPublisherConfig {

    @Bean
    @ConditionalOnProperty(name = "wallet.events.publisher", havingValue = "sns")
    EventPublisher snsEventPublisher(SnsClient sns,
                                     @Value("${wallet.events.transaction-topic-arn}") String transactionTopicArn,
                                     @Value("${wallet.events.account-topic-arn}") String accountTopicArn) {
        return new SnsEventPublisher(sns, transactionTopicArn, accountTopicArn);
    }

    @Bean(destroyMethod = "close")
    @ConditionalOnProperty(name = "wallet.events.publisher", havingValue = "sns")
    SnsClient snsClient(@Value("${wallet.events.region:us-east-1}") String region,
                        @Value("${wallet.events.endpoint:}") String endpoint,
                        @Value("${wallet.events.access-key:}") String accessKey,
                        @Value("${wallet.events.secret-key:}") String secretKey) {
        var builder = SnsClient.builder()
                .httpClientBuilder(UrlConnectionHttpClient.builder())
                .region(Region.of(region))
                .credentialsProvider(credentials(accessKey, secretKey));
        if (!endpoint.isBlank()) {
            builder.endpointOverride(URI.create(endpoint));
        }
        return builder.build();
    }

    @Bean
    @ConditionalOnProperty(name = "wallet.events.publisher", havingValue = "log", matchIfMissing = true)
    EventPublisher loggingEventPublisher() {
        return new LoggingEventPublisher();
    }

    private static AwsCredentialsProvider credentials(String accessKey, String secretKey) {
        if (!accessKey.isBlank()) {
            return StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secretKey));
        }
        return DefaultCredentialsProvider.builder().build();
    }
}
