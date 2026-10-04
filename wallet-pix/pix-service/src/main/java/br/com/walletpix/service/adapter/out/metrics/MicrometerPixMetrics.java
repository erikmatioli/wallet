package br.com.walletpix.service.adapter.out.metrics;

import br.com.walletpix.service.application.port.PixPorts.PixMetrics;
import br.com.walletpix.service.domain.PixPayment;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.EnumSet;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * {@code pix_payments_total{direction,status,reason,ispb}} counts state changes (one increment
 * per payment per state reached), {@code pix_inbound_messages_total{type,result}} every message
 * handled, including duplicates - a rising "duplicate" share is how redelivery storms show up.
 */
@Component
class MicrometerPixMetrics implements PixMetrics {

    private final MeterRegistry registry;

    MicrometerPixMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    @Override
    public void inboundMessage(String messageType, String result) {
        Counter.builder("pix.inbound.messages.total")
                .description("Bus messages handled, by type and outcome")
                .tag("type", messageType)
                .tag("result", result)
                .register(registry)
                .increment();
    }

    @Override
    public void payment(PixPayment p) {
        Counter.builder("pix.payments.total")
                .description("Pix payments reaching a state, by direction, status and reason")
                .tag("direction", p.direction().name())
                .tag("status", p.status().name())
                .tag("reason", p.reasonCode() == null ? "none" : p.reasonCode())
                .tag("ispb", p.ispb())
                .register(registry)
                .increment();
        if (TERMINAL.contains(p.status())) {
            // First state to last: INBOUND = pacs.008 received -> credited/rejected, OUTBOUND =
            // debit -> settled/refunded/returned. Histogram, so p95/p99 come from Prometheus.
            Timer.builder("pix.payment.duration")
                    .description("Time from the first to the terminal state of a Pix")
                    .tag("direction", p.direction().name())
                    .tag("status", p.status().name())
                    .publishPercentileHistogram()
                    .register(registry)
                    .record(Duration.between(p.createdAt(), p.updatedAt()));
        }
    }

    private static final Set<PixPayment.Status> TERMINAL = EnumSet.of(PixPayment.Status.REJECTED,
            PixPayment.Status.CREDITED, PixPayment.Status.COMPLETED, PixPayment.Status.REFUNDED,
            PixPayment.Status.RETURNED);
}
