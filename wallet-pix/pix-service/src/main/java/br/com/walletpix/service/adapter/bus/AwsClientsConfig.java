package br.com.walletpix.service.adapter.bus;

import br.com.walletpix.service.config.PixProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sqs.SqsClient;

/**
 * SNS and SQS clients. With {@code pix.bus.endpoint} set (LocalStack) they use that endpoint and
 * the static keys given; without it, the default AWS endpoint and credential chain - nothing in
 * the code changes between the two.
 */
@Configuration(proxyBeanMethods = false)
class AwsClientsConfig {

    @Bean(destroyMethod = "close")
    SqsClient sqsClient(PixProperties props) {
        var builder = SqsClient.builder()
                .httpClientBuilder(UrlConnectionHttpClient.builder())
                .region(Region.of(props.bus().region()))
                .credentialsProvider(credentials(props.bus()));
        if (props.bus().endpoint() != null) {
            builder.endpointOverride(props.bus().endpoint());
        }
        return builder.build();
    }

    @Bean(destroyMethod = "close")
    SnsClient snsClient(PixProperties props) {
        var builder = SnsClient.builder()
                .httpClientBuilder(UrlConnectionHttpClient.builder())
                .region(Region.of(props.bus().region()))
                .credentialsProvider(credentials(props.bus()));
        if (props.bus().endpoint() != null) {
            builder.endpointOverride(props.bus().endpoint());
        }
        return builder.build();
    }

    private static AwsCredentialsProvider credentials(PixProperties.Bus bus) {
        if (bus.accessKey() != null && !bus.accessKey().isBlank()) {
            return StaticCredentialsProvider.create(AwsBasicCredentials.create(bus.accessKey(), bus.secretKey()));
        }
        return DefaultCredentialsProvider.builder().build();
    }
}
