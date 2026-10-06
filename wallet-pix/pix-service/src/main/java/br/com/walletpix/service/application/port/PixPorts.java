package br.com.walletpix.service.application.port;

import br.com.walletpix.messages.PixEvents.PixEvent;
import br.com.walletpix.messages.SpiMessages.Envelope;
import br.com.walletpix.service.domain.HolderCheckResult;
import br.com.walletpix.service.domain.PixPayment;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/** Outbound ports of the Pix use cases. Implemented by the adapters; faked in-memory in tests. */
public final class PixPorts {

    private PixPorts() {
    }

    public interface PixPaymentRepository {

        Optional<PixPayment> find(String endToEndId, PixPayment.Direction direction);

        /** The payment whose pacs.008 had this message id - what a pacs.002's OrgnlMsgId points at. */
        Optional<PixPayment> findByPacs008MsgId(String msgId);

        Optional<PixPayment> findByRequestId(String requestId);

        void insert(PixPayment payment);

        /**
         * Optimistic update: applies only if the stored version is still {@code payment.version()}.
         *
         * @throws ConcurrentUpdateException when another consumer changed it first
         */
        void update(PixPayment payment);
    }

    /** Duplicate detection: SQS delivers at least once, and the SPI may resend. */
    public interface InboundMessageLog {

        boolean alreadyProcessed(String messageKey);

        /**
         * Records the message as processed, inside the caller's transaction.
         *
         * @return false when it was already recorded (a concurrent delivery won): the caller
         *         must then skip its changes
         */
        boolean markProcessed(String messageKey, String messageType);
    }

    /** Messages to send, written to the transactional outbox: they leave only if the state change commits. */
    public interface OutboundMessages {

        void sendToSpi(Envelope<?> message);

        void publishEvent(PixEvent event);
    }

    /**
     * wallet-core, as the tenant that owns the ISPB. Receiving uses the holder check and credits;
     * sending debits (withdrawal) and, on rejection, reverses that exact debit. Every write is
     * idempotent on the wallet-core side, so a retried message never moves money twice.
     */
    public interface WalletCore {

        HolderCheckResult checkHolder(String ispb, String branch, String number, String checkDigit, String taxId);

        /**
         * Idempotent credit of a Pix received (PIX_IN) or, when {@code pix} has a return id, of a
         * return received (PIX_RETURN_IN). Calling again with the same key returns the original
         * transaction id.
         *
         * @return wallet-core transaction id
         */
        UUID credit(String ispb, UUID accountId, long amountCents, String description, String idempotencyKey,
                    PixRecord pix);

        /** The payer's account as wallet-core knows it; empty when it doesn't exist for this tenant. */
        Optional<PayerAccount> findAccount(String ispb, UUID accountId);

        /**
         * Idempotent debit of a Pix sent (PIX_OUT), made with a token restricted to {@code pix:send}.
         * The same key and payload return the original debit - with the EndToEndId stored by the
         * first attempt, which may differ from {@code pix.endToEndId()}; the same key with another
         * payload is refused.
         */
        DebitResult debit(String ispb, UUID accountId, long amountCents, String description, String idempotencyKey,
                          PixRecord pix);

        /**
         * Credits back exactly the given debit (a PIX_REFUND in wallet-core); at most once per debit
         * (wallet-core enforces it).
         */
        UUID reverse(String ispb, UUID debitTransactionId, String description, String reasonCode);
    }

    /**
     * What wallet-core records about the Pix next to the money movement (ADR-010 of wallet-core),
     * so the customer's statement shows it without asking this service.
     *
     * @param returnId             only for a return
     * @param relatedTransactionId the original Pix's wallet-core transaction, only for a return
     * @param reasonCode           only for a return
     */
    public record PixRecord(String endToEndId, String returnId, UUID relatedTransactionId, Counterparty counterparty,
                            String reasonCode, String remittanceInfo) {

        public static PixRecord of(String endToEndId, Counterparty counterparty, String remittanceInfo) {
            return new PixRecord(endToEndId, null, null, counterparty, null, remittanceInfo);
        }
    }

    /** The other side of the Pix: the payee of a Pix sent, the payer of a Pix received. */
    public record Counterparty(String name, String taxId, String ispb, String branch, String accountNumber) {
    }

    /** {@code accountWithDigit}: account number with the check digit appended, as on the bus. */
    public record PayerAccount(UUID id, String branch, String accountWithDigit, String holderName) {
    }

    public sealed interface DebitResult {
        /** {@code endToEndId}: the one wallet-core stored with the debit - use it for the Pix. */
        record Debited(UUID transactionId, String endToEndId) implements DebitResult {
        }

        /** wallet-core refused the debit (e.g. INSUFFICIENT_FUNDS, ACCOUNT_NOT_ACTIVE); nothing was moved. */
        record Refused(String code, String detail) implements DebitResult {
        }
    }

    /**
     * A rule an outgoing Pix must pass before the payer is debited (amount limits, time windows,
     * per-customer caps...). Policies run in order; the first refusal stops the payment.
     */
    public interface PaymentPolicy {

        /** @return the refusal, or empty to let the payment go on to the next policy */
        Optional<Refusal> evaluate(PaymentAttempt attempt);

        record PaymentAttempt(String ispb, UUID payerAccountId, String payeeIspb, long amountCents,
                              java.time.Instant at) {
        }

        record Refusal(String code, String message) {
        }
    }

    public interface Transactions {
        <T> T inTransaction(Supplier<T> work);
    }

    /** Which ISPBs this service answers for (one per wallet-core tenant it holds credentials of). */
    public interface Participants {
        boolean isOurs(String ispb);
    }

    public interface PixMetrics {
        void inboundMessage(String messageType, String result);

        void payment(PixPayment payment);
    }

    public static final class ConcurrentUpdateException extends RuntimeException {
        public ConcurrentUpdateException(String message) {
            super(message);
        }
    }
}
