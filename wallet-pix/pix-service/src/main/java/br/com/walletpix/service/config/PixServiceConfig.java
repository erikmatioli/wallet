package br.com.walletpix.service.config;

import br.com.walletpix.service.adapter.bus.SqsQueueConsumer;
import br.com.walletpix.service.adapter.in.sqs.SpiInboundHandler;
import br.com.walletpix.service.application.MaxAmountPolicy;
import br.com.walletpix.service.application.ReceivePixService;
import br.com.walletpix.service.application.SendPixService;
import br.com.walletpix.service.application.StatusReportRouter;
import br.com.walletpix.service.application.port.PixPorts.InboundMessageLog;
import br.com.walletpix.service.application.port.PixPorts.OutboundMessages;
import br.com.walletpix.service.application.port.PixPorts.Participants;
import br.com.walletpix.service.application.port.PixPorts.PaymentPolicy;
import br.com.walletpix.service.application.port.PixPorts.PixMetrics;
import br.com.walletpix.service.application.port.PixPorts.PixPaymentRepository;
import br.com.walletpix.service.application.port.PixPorts.Transactions;
import br.com.walletpix.service.application.port.PixPorts.WalletCore;
import java.time.Clock;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.transaction.support.TransactionTemplate;
import software.amazon.awssdk.services.sqs.SqsClient;
import tools.jackson.databind.json.JsonMapper;

/** Composition root: the framework-free use cases, wired to the adapters. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(PixProperties.class)
@EnableScheduling
class PixServiceConfig {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    Transactions transactions(TransactionTemplate template) {
        return new Transactions() {
            @Override
            public <T> T inTransaction(java.util.function.Supplier<T> work) {
                return template.execute(status -> work.get());
            }
        };
    }

    @Bean
    Participants participants(PixProperties props) {
        Set<String> ours = props.participants().stream().map(PixProperties.Participant::ispb).collect(Collectors.toSet());
        return ours::contains;
    }

    @Bean
    ReceivePixService receivePixService(WalletCore walletCore, PixPaymentRepository payments, InboundMessageLog log,
                                        OutboundMessages outbound, Transactions tx, Participants participants,
                                        PixMetrics metrics, Clock clock) {
        return new ReceivePixService(walletCore, payments, log, outbound, tx, participants, metrics, clock);
    }

    /** Rules every outgoing Pix must pass before the debit, in order. Add time windows, daily caps... here. */
    @Bean
    List<PaymentPolicy> paymentPolicies(PixProperties props) {
        return List.of(new MaxAmountPolicy(props.policies().maxAmount()));
    }

    @Bean
    SendPixService sendPixService(WalletCore walletCore, PixPaymentRepository payments, InboundMessageLog log,
                                  OutboundMessages outbound, Transactions tx, Participants participants,
                                  List<PaymentPolicy> paymentPolicies, PixMetrics metrics, Clock clock) {
        return new SendPixService(walletCore, payments, log, outbound, tx, participants, paymentPolicies, metrics,
                clock);
    }

    @Bean
    StatusReportRouter statusReportRouter(PixPaymentRepository payments, InboundMessageLog log,
                                          ReceivePixService receive, SendPixService send, PixMetrics metrics) {
        return new StatusReportRouter(payments, log, receive, send, metrics);
    }

    /** The consumer can be switched off (pix.bus.consumers.enabled=false), e.g. in tests that drive the use cases directly. */
    @Bean
    @ConditionalOnProperty(name = "pix.bus.consumers.enabled", havingValue = "true", matchIfMissing = true)
    SqsQueueConsumer spiInboundConsumer(SqsClient sqs, PixProperties props, JsonMapper json, ReceivePixService receive,
                                        SendPixService send, StatusReportRouter router) {
        return new SqsQueueConsumer(sqs, props.bus().spiInboundQueue(), new SpiInboundHandler(json, receive, send, router));
    }
}
