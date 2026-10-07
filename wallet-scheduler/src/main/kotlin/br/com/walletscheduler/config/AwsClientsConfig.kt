package br.com.walletscheduler.config

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.sqs.SqsClient

/** AWS SDK v2 directly, as wallet-pix: one less framework to keep compatible with Spring Boot 4. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty("scheduler.bus.enabled", havingValue = "true", matchIfMissing = true)
class AwsClientsConfig {

    @Bean(destroyMethod = "close")
    fun sqsClient(props: SchedulerProperties): SqsClient {
        val bus = props.bus
        val builder = SqsClient.builder()
            .httpClientBuilder(UrlConnectionHttpClient.builder())
            .region(Region.of(bus.region))
            .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(bus.accessKey, bus.secretKey)))
        bus.endpoint?.let { builder.endpointOverride(it) }
        return builder.build()
    }
}
