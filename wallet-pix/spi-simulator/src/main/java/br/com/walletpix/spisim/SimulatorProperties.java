package br.com.walletpix.spisim;

import java.net.URI;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param participants      ISPBs served by the Pix service: orders between them are routed to it on both sides
 * @param externalIspb      a fictitious PSP the simulator plays itself (accepts, or rejects amounts ending in ,99)
 * @param spiToPspTopicArn  where the simulator publishes (the Pix service's inbound queue subscribes)
 * @param inboundQueue      queue the simulator consumes (subscribed to the PSP-to-SPI topic)
 */
@ConfigurationProperties("spi")
public record SimulatorProperties(Set<String> participants, String externalIspb, URI endpoint, String region,
                                  String accessKey, String secretKey, String spiToPspTopicArn, String inboundQueue) {
}
