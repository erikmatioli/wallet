package br.com.walletpix.service.config;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param participants one entry per wallet-core tenant this service answers for: the ISPB that
 *                     the SPI addresses, and the tenant credentials used to call wallet-core
 */
@ConfigurationProperties("pix")
public record PixProperties(WalletCore walletCore, List<Participant> participants, Bus bus, Policies policies) {

    /** @param jwkSetUri wallet-core's JWKS, used to validate the callers' tokens on the REST API */
    public record WalletCore(URI baseUrl, URI jwkSetUri, String issuer, Duration connectTimeout,
                             Duration readTimeout) {
    }

    public record Participant(String ispb, String clientId, String clientSecret) {
    }

    /**
     * @param endpoint     SNS/SQS endpoint override (LocalStack locally); empty for real AWS
     * @param pspToSpiTopicArn         where pacs.002/pacs.008 we emit go
     * @param paymentEventsTopicArn    where PixEvent notifications go
     * @param spiInboundQueue          queue name: messages from the SPI (pacs.008/002/004)
     */
    public record Bus(URI endpoint, String region, String accessKey, String secretKey, String pspToSpiTopicArn,
                      String paymentEventsTopicArn, String spiInboundQueue) {
    }

    /** @param maxAmount largest single outgoing Pix accepted (MaxAmountPolicy) */
    public record Policies(java.math.BigDecimal maxAmount) {
    }
}
