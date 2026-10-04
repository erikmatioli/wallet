package br.com.walletpix.service.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * One Pix as seen by this PSP. The same EndToEndId can exist twice in the database when both
 * sides are ours (a Pix between two of our tenants): once OUTBOUND for the payer's PSP and once
 * INBOUND for the payee's - which is why identity is (endToEndId, direction), and why SPI status
 * reports are matched on {@code pacs008MsgId} (the message each side saw), not on the E2E alone.
 *
 * <pre>
 * INBOUND : ACCEPTED --ACSC--> CREDITED         OUTBOUND: SENT --ACSC--> COMPLETED --pacs.004--> RETURNED
 *           ACCEPTED --RJCT--> REJECTED                   SENT --RJCT--> REFUNDED (debit reversed)
 *           (or REJECTED straight away when the holder check fails)
 * </pre>
 *
 * Intermediate states such as "settled but not yet credited" are never persisted: the credit
 * is made (idempotently) before the state change commits, so a crash in between simply replays
 * the message and the credit returns the original transaction.
 *
 * @param ispb             our participant (the tenant) - payee's ISPB if INBOUND, payer's if OUTBOUND
 * @param walletAccountId  our customer's wallet-core account (payee if INBOUND, payer if OUTBOUND)
 * @param debitTransactionId  OUTBOUND only: the wallet-core withdrawal that debited the payer
 *                            before the pacs.008 left - what a rejection reverses
 * @param walletTransactionId the credit (INBOUND), or the reversal / return credit (OUTBOUND), once made
 */
public record PixPayment(
        UUID id,
        String endToEndId,
        Direction direction,
        Status status,
        String ispb,
        String counterpartIspb,
        String pacs008MsgId,
        long amountCents,
        PartyAccount payer,
        PartyAccount payee,
        UUID walletAccountId,
        UUID debitTransactionId,
        String requestId,
        String reasonCode,
        String description,
        UUID walletTransactionId,
        long version,
        Instant createdAt,
        Instant updatedAt) {

    public enum Direction { INBOUND, OUTBOUND }

    public enum Status { ACCEPTED, REJECTED, CREDITED, SENT, COMPLETED, REFUNDED, RETURNED }

    /** Name, CPF/CNPJ (digits only), branch and account number with check digit. */
    public record PartyAccount(String name, String taxId, String branch, String accountNumber) {
    }

    public PixPayment {
        if (amountCents <= 0) {
            throw new IllegalArgumentException("amount must be positive");
        }
    }

    // ------------------------------------------------------------------ INBOUND (we are the payee's PSP)

    public static PixPayment acceptedIncoming(String endToEndId, String ispb, String counterpartIspb, String msgId,
                                              long amountCents, PartyAccount payer, PartyAccount payee,
                                              UUID walletAccountId, String description, Instant now) {
        return new PixPayment(UUID.randomUUID(), endToEndId, Direction.INBOUND, Status.ACCEPTED, ispb, counterpartIspb,
                msgId, amountCents, payer, payee, walletAccountId, null, null, null, description, null, 0, now, now);
    }

    public static PixPayment rejectedIncoming(String endToEndId, String ispb, String counterpartIspb, String msgId,
                                              long amountCents, PartyAccount payer, PartyAccount payee,
                                              RejectionReason reason, String description, Instant now) {
        return new PixPayment(UUID.randomUUID(), endToEndId, Direction.INBOUND, Status.REJECTED, ispb, counterpartIspb,
                msgId, amountCents, payer, payee, null, null, null, reason.name(), description, null, 0, now, now);
    }

    public PixPayment credited(UUID transactionId, Instant now) {
        require(Direction.INBOUND, Status.ACCEPTED);
        return with(Status.CREDITED, reasonCode, transactionId, now);
    }

    /** The SPI rejected a Pix we had accepted (e.g. timeout on its side): nothing is credited. */
    public PixPayment cancelledBySpi(String spiReasonCode, Instant now) {
        require(Direction.INBOUND, Status.ACCEPTED);
        return with(Status.REJECTED, spiReasonCode, null, now);
    }

    // ------------------------------------------------------------------ OUTBOUND (we are the payer's PSP)

    public static PixPayment sentOutgoing(String endToEndId, String ispb, String counterpartIspb, String msgId,
                                          long amountCents, PartyAccount payer, PartyAccount payee,
                                          UUID walletAccountId, UUID debitTransactionId, String requestId,
                                          String description, Instant now) {
        if (debitTransactionId == null) {
            throw new IllegalArgumentException("an outgoing Pix is only sent after the payer was debited");
        }
        return new PixPayment(UUID.randomUUID(), endToEndId, Direction.OUTBOUND, Status.SENT, ispb, counterpartIspb,
                msgId, amountCents, payer, payee, walletAccountId, debitTransactionId, requestId, null, description,
                null, 0, now, now);
    }

    public PixPayment completed(Instant now) {
        require(Direction.OUTBOUND, Status.SENT);
        return with(Status.COMPLETED, null, null, now);
    }

    /** The SPI (or the payee's PSP) rejected our Pix and the debit was reversed. */
    public PixPayment refunded(String spiReasonCode, UUID reversalTransactionId, Instant now) {
        require(Direction.OUTBOUND, Status.SENT);
        return with(Status.REFUNDED, spiReasonCode, reversalTransactionId, now);
    }

    /** The payee's PSP returned the money (pacs.004) and it was credited to the payer. */
    public PixPayment returned(String returnReasonCode, UUID returnCreditTransactionId, Instant now) {
        require(Direction.OUTBOUND, Status.COMPLETED);
        return with(Status.RETURNED, returnReasonCode, returnCreditTransactionId, now);
    }

    // ------------------------------------------------------------------ helpers

    private void require(Direction expectedDirection, Status expectedStatus) {
        if (direction != expectedDirection || status != expectedStatus) {
            throw new InvalidTransitionException(endToEndId, direction, status, expectedStatus);
        }
    }

    private PixPayment with(Status newStatus, String newReason, UUID newTransactionId, Instant now) {
        return new PixPayment(id, endToEndId, direction, newStatus, ispb, counterpartIspb, pacs008MsgId, amountCents,
                payer, payee, walletAccountId, debitTransactionId, requestId, newReason, description,
                newTransactionId != null ? newTransactionId : walletTransactionId, version, createdAt, now);
    }

    public static final class InvalidTransitionException extends RuntimeException {
        public InvalidTransitionException(String endToEndId, Direction direction, Status actual, Status expected) {
            super("Pix " + endToEndId + " (" + direction + ") is " + actual + ", expected " + expected);
        }
    }
}
