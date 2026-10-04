package br.com.walletpix.messages;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Events the Pix service publishes as payments progress - the internal contract with the rest
 * of the platform (not SPI messages). Outgoing Pix are started synchronously through the
 * service's REST API ({@code POST /v1/pix/payments}), not through the bus.
 */
public final class PixEvents {

    private PixEvents() {
    }

    /** SNS message attribute carrying the event/command type, so subscribers can filter. */
    public static final String TYPE_ATTRIBUTE = "eventType";

    public enum EventType {
        /** Incoming Pix settled and credited to our customer. */
        PIX_RECEIVED,
        /** Incoming Pix we rejected (reason code set). */
        PIX_RECEIVE_REJECTED,
        /** Outgoing Pix settled by the SPI. */
        PIX_SENT_COMPLETED,
        /** Outgoing Pix rejected; the payer's debit was reversed (reason code set). */
        PIX_SENT_REFUNDED,
        /** A return (pacs.004) of an outgoing Pix arrived and was credited to the payer. */
        PIX_RETURN_RECEIVED
    }

    /**
     * Published on every terminal step of a payment. Carries no CPF/CNPJ or names, same rule as
     * wallet-core's outbox events.
     */
    public record PixEvent(
            EventType type,
            String endToEndId,
            String requestId,
            String ispb,
            UUID walletAccountId,
            BigDecimal amount,
            String reasonCode,
            UUID walletTransactionId,
            Instant occurredAt) {
    }
}
