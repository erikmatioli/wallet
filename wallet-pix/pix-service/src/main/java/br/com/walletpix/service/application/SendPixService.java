package br.com.walletpix.service.application;

import br.com.walletpix.messages.PixEvents.EventType;
import br.com.walletpix.messages.SpiMessages.AppHdr;
import br.com.walletpix.messages.SpiMessages.MsgType;
import br.com.walletpix.messages.SpiMessages.Pacs004;
import br.com.walletpix.messages.SpiMessages.TxInf;
import br.com.walletpix.messages.SpiMessages.TxInfAndSts;
import br.com.walletpix.messages.SpiMessages.TxStatus;
import br.com.walletpix.service.application.MessageFailures.PermanentFailure;
import br.com.walletpix.service.application.MessageFailures.RetryLater;
import br.com.walletpix.service.application.port.PixPorts.Counterparty;
import br.com.walletpix.service.application.port.PixPorts.DebitResult;
import br.com.walletpix.service.application.port.PixPorts.InboundMessageLog;
import br.com.walletpix.service.application.port.PixPorts.OutboundMessages;
import br.com.walletpix.service.application.port.PixPorts.PayerAccount;
import br.com.walletpix.service.application.port.PixPorts.PaymentPolicy;
import br.com.walletpix.service.application.port.PixPorts.PaymentPolicy.PaymentAttempt;
import br.com.walletpix.service.application.port.PixPorts.Participants;
import br.com.walletpix.service.application.port.PixPorts.PixMetrics;
import br.com.walletpix.service.application.port.PixPorts.PixPaymentRepository;
import br.com.walletpix.service.application.port.PixPorts.PixRecord;
import br.com.walletpix.service.application.port.PixPorts.Transactions;
import br.com.walletpix.service.application.port.PixPorts.WalletCore;
import br.com.walletpix.service.domain.Amounts;
import br.com.walletpix.service.domain.HolderCheckResult;
import br.com.walletpix.service.domain.PixPayment;
import br.com.walletpix.service.domain.PixPayment.Direction;
import br.com.walletpix.service.domain.PixPayment.PartyAccount;
import br.com.walletpix.service.domain.PixPayment.Status;
import br.com.walletpix.service.domain.SpiIds;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Outgoing Pix - we are the payer's PSP.
 * <ol>
 *   <li>{@link #initiate} (synchronous, from the REST API): policies, payer check, debit
 *       (withdrawal), then the payment is recorded as SENT and its pacs.008 goes to the outbox.
 *       A refused debit stops everything: no pacs.008 ever leaves without money behind it.</li>
 *   <li>pacs.002 ACSC: done. pacs.002 RJCT: reverse that exact debit in wallet-core.</li>
 *   <li>pacs.004 (the payee's PSP returned the money): credit the payer (key {@code pix-return-<RtrId>}).</li>
 * </ol>
 */
public final class SendPixService {

    /** What the caller asked for. {@code requestId} is the caller's Idempotency-Key. */
    public record InitiateCommand(String ispb, String requestId, UUID payerAccountId, String payerTaxId,
                                  String payeeIspb, String payeeBranch, String payeeAccountNumber, String payeeTaxId,
                                  String payeeName, BigDecimal amount, String description) {
    }

    /** {@code replayed}: the same Idempotency-Key was seen before, and this is the original payment. */
    public record InitiateResult(PixPayment payment, boolean replayed) {
    }

    /**
     * The payment was refused before anything was sent; with a stable {@code code}
     * (POLICY codes, PAYER_*, INSUFFICIENT_FUNDS, IDEMPOTENCY_KEY_REUSED...).
     */
    public static final class PaymentRefused extends RuntimeException {
        private final String code;

        public PaymentRefused(String code, String message) {
            super(message);
            this.code = code;
        }

        public String code() {
            return code;
        }
    }

    private final WalletCore walletCore;
    private final PixPaymentRepository payments;
    private final InboundMessageLog messageLog;
    private final OutboundMessages outbound;
    private final Transactions tx;
    private final Participants participants;
    private final List<PaymentPolicy> policies;
    private final PixMetrics metrics;
    private final Clock clock;

    public SendPixService(WalletCore walletCore, PixPaymentRepository payments, InboundMessageLog messageLog,
                          OutboundMessages outbound, Transactions tx, Participants participants,
                          List<PaymentPolicy> policies, PixMetrics metrics, Clock clock) {
        this.walletCore = walletCore;
        this.payments = payments;
        this.messageLog = messageLog;
        this.outbound = outbound;
        this.tx = tx;
        this.participants = participants;
        this.policies = List.copyOf(policies);
        this.metrics = metrics;
        this.clock = clock;
    }

    // ------------------------------------------------------------------ step 1: initiate (synchronous)

    public InitiateResult initiate(InitiateCommand c) {
        validate(c);
        long cents = Amounts.toCents(c.amount());
        Optional<PixPayment> existing = payments.findByRequestId(c.requestId());
        if (existing.isPresent()) {
            return replay(existing.get(), c, cents);
        }
        Instant now = clock.instant();
        for (PaymentPolicy policy : policies) {
            Optional<PaymentPolicy.Refusal> refusal = policy.evaluate(
                    new PaymentAttempt(c.ispb(), c.payerAccountId(), c.payeeIspb(), cents, now));
            if (refusal.isPresent()) {
                metrics.inboundMessage("initiate", "policy-" + refusal.get().code());
                throw new PaymentRefused(refusal.get().code(), refusal.get().message());
            }
        }

        // The payer's document must be the account holder's: it goes into the pacs.008 (Dbtr.Id)
        // and the payee's PSP shows it to the receiver. The name comes from wallet-core, not the caller.
        PayerAccount payer = walletCore.findAccount(c.ispb(), c.payerAccountId())
                .orElseThrow(() -> new PaymentRefused("PAYER_ACCOUNT_NOT_FOUND", "payer account not found"));
        String acc = payer.accountWithDigit();
        HolderCheckResult holder = walletCore.checkHolder(c.ispb(), payer.branch(), acc.substring(0, acc.length() - 1),
                acc.substring(acc.length() - 1), c.payerTaxId());
        if (holder.outcome() != HolderCheckResult.Outcome.VALID) {
            throw new PaymentRefused("PAYER_" + holder.outcome().name(),
                    "payer account cannot send: " + holder.outcome());
        }
        if (!c.payerAccountId().equals(holder.accountId())) {
            // Defensive: the number we looked up resolved to another account. Never debit then.
            throw new PaymentRefused("PAYER_ACCOUNT_MISMATCH", "payer account does not match its own number");
        }

        // Atomic in wallet-core: no balance pre-check here, the debit itself refuses an overdraft.
        // The description must be the same on every retry of this request - it is part of the
        // idempotency fingerprint. The EndToEndId is not: each attempt mints one, and a retry after
        // a crash gets back the one stored with the first debit, which is the one to send.
        DebitResult debit = walletCore.debit(c.ispb(), c.payerAccountId(), cents,
                limit("Pix enviado para " + c.payeeName()), "pix-debit-" + c.requestId(),
                PixRecord.of(SpiIds.newEndToEndId(c.ispb(), now),
                        new Counterparty(c.payeeName(), digits(c.payeeTaxId()), c.payeeIspb(), c.payeeBranch(),
                                c.payeeAccountNumber()),
                        c.description()));
        if (debit instanceof DebitResult.Refused refused) {
            metrics.inboundMessage("initiate", "debit-" + refused.code());
            throw new PaymentRefused(refused.code(), refused.detail());
        }
        DebitResult.Debited debited = (DebitResult.Debited) debit;
        UUID debitId = debited.transactionId();

        PixPayment payment = PixPayment.sentOutgoing(debited.endToEndId(), c.ispb(), c.payeeIspb(),
                SpiIds.newMessageId(c.ispb()), cents,
                new PartyAccount(payer.holderName(), digits(c.payerTaxId()), payer.branch(), acc),
                new PartyAccount(c.payeeName(), digits(c.payeeTaxId()), c.payeeBranch(), c.payeeAccountNumber()),
                c.payerAccountId(), debitId, c.requestId(), c.description(), now);
        boolean committed = commit("request:" + c.requestId(), "initiate", () -> {
            payments.insert(payment);
            outbound.sendToSpi(SpiMessageFactory.paymentOrder(payment, now));
        });
        if (!committed) {
            // A concurrent call with the same Idempotency-Key got there first; both debits were the
            // same withdrawal (same key), so return its payment and send nothing more.
            return replay(payments.findByRequestId(c.requestId()).orElseThrow(), c, cents);
        }
        metrics.inboundMessage("initiate", "sent");
        metrics.payment(payment);
        return new InitiateResult(payment, false);
    }

    private static InitiateResult replay(PixPayment p, InitiateCommand c, long cents) {
        boolean same = p.amountCents() == cents && p.ispb().equals(c.ispb())
                && Objects.equals(p.walletAccountId(), c.payerAccountId())
                && p.counterpartIspb().equals(c.payeeIspb())
                && Objects.equals(p.payee().accountNumber(), c.payeeAccountNumber());
        if (!same) {
            throw new PaymentRefused("IDEMPOTENCY_KEY_REUSED",
                    "this Idempotency-Key was already used for a different payment");
        }
        return new InitiateResult(p, true);
    }

    private void validate(InitiateCommand c) {
        if (c.requestId() == null || c.requestId().isBlank() || c.requestId().length() > 64) {
            throw new PaymentRefused("IDEMPOTENCY_KEY_REQUIRED", "Idempotency-Key is required (max 64 characters)");
        }
        if (!participants.isOurs(c.ispb())) {
            throw new PaymentRefused("NOT_A_PARTICIPANT", "ISPB " + c.ispb() + " is not served by this service");
        }
        if (c.payerAccountId() == null || c.payerTaxId() == null) {
            throw new PaymentRefused("INVALID_REQUEST", "payerAccountId and payerTaxId are required");
        }
        if (c.payeeIspb() == null || !c.payeeIspb().matches("\\d{8}")
                || c.payeeBranch() == null || !c.payeeBranch().matches("\\d{4}")
                || c.payeeAccountNumber() == null || !c.payeeAccountNumber().matches("\\d{2,21}")
                || c.payeeTaxId() == null || c.payeeName() == null || c.payeeName().isBlank()) {
            throw new PaymentRefused("INVALID_PAYEE",
                    "payee needs ispb (8 digits), branch (4), accountNumber with check digit, taxId and name");
        }
        try {
            Amounts.toCents(c.amount());
        } catch (IllegalArgumentException e) {
            throw new PaymentRefused("INVALID_AMOUNT", e.getMessage());
        }
    }

    // ------------------------------------------------------------------ step 2: outcome (pacs.002 from the SPI)

    /** Called by {@link StatusReportRouter} once it knows the report is about an OUTBOUND payment. */
    void onStatusReport(AppHdr hdr, PixPayment payment, TxInfAndSts status) {
        String messageKey = hdr.bizMsgIdr();
        if (payment.status() != Status.SENT) {
            commit(messageKey, MsgType.PACS_002, () -> { }); // duplicate: already completed or refunded
            metrics.inboundMessage(MsgType.PACS_002, "ignored-" + payment.status().name().toLowerCase());
            return;
        }
        switch (status.txSts()) {
            case TxStatus.ACSC -> {
                PixPayment done = payment.completed(clock.instant());
                if (commit(messageKey, MsgType.PACS_002, () -> {
                    payments.update(done);
                    outbound.publishEvent(PixEventFactory.of(EventType.PIX_SENT_COMPLETED, done));
                })) {
                    metrics.payment(done);
                }
            }
            case TxStatus.RJCT -> {
                // Reverses exactly the debit made for this Pix (a PIX_REFUND in wallet-core); one
                // reversal per debit, so a redelivered RJCT gets the same reversal back.
                UUID reversalId = walletCore.reverse(payment.ispb(), payment.debitTransactionId(),
                        limit("Estorno de Pix não concluído (" + status.rsnCd() + ")"), status.rsnCd());
                PixPayment refunded = payment.refunded(status.rsnCd(), reversalId, clock.instant());
                if (commit(messageKey, MsgType.PACS_002, () -> {
                    payments.update(refunded);
                    outbound.publishEvent(PixEventFactory.of(EventType.PIX_SENT_REFUNDED, refunded));
                })) {
                    metrics.payment(refunded);
                }
            }
            default -> commit(messageKey, MsgType.PACS_002, () -> { });
        }
    }

    // ------------------------------------------------------------------ step 3: return (pacs.004)

    public void onReturn(AppHdr hdr, Pacs004 ret) {
        String messageKey = hdr.bizMsgIdr();
        if (messageLog.alreadyProcessed(messageKey)) {
            metrics.inboundMessage(MsgType.PACS_004, "duplicate");
            return;
        }
        TxInf t = ret.txInf();
        PixPayment payment = payments.find(t.orgnlEndToEndId(), Direction.OUTBOUND)
                .orElseThrow(() -> new RetryLater("pacs.004 for unknown outgoing Pix " + t.orgnlEndToEndId()));
        if (payment.status() == Status.RETURNED) {
            commit(messageKey, MsgType.PACS_004, () -> { });
            return;
        }
        if (payment.status() != Status.COMPLETED) {
            throw new PermanentFailure("pacs.004 for Pix " + payment.endToEndId() + " in status " + payment.status());
        }
        long cents = Amounts.toCents(t.rtrdIntrBkSttlmAmt().amt());
        if (cents > payment.amountCents()) {
            throw new PermanentFailure("return of " + cents + " cents exceeds the original " + payment.amountCents());
        }
        // A return is new money coming back (possibly partial), not an undo of our debit: credit,
        // as a PIX_RETURN_IN tied to the original debit (wallet-core caps returns at its amount).
        PartyAccount payee = payment.payee();
        UUID creditId = walletCore.credit(payment.ispb(), payment.walletAccountId(), cents,
                limit("Devolução de Pix (" + t.rtrRsnCd() + ") - " + payment.endToEndId()), "pix-return-" + t.rtrId(),
                new PixRecord(payment.endToEndId(), t.rtrId(), payment.debitTransactionId(),
                        new Counterparty(payee.name(), payee.taxId(), payment.counterpartIspb(), payee.branch(),
                                payee.accountNumber()),
                        t.rtrRsnCd(), null));
        PixPayment returned = payment.returned(t.rtrRsnCd(), creditId, clock.instant());
        if (commit(messageKey, MsgType.PACS_004, () -> {
            payments.update(returned);
            outbound.publishEvent(PixEventFactory.of(EventType.PIX_RETURN_RECEIVED, returned));
        })) {
            metrics.inboundMessage(MsgType.PACS_004, "credited");
            metrics.payment(returned);
        }
    }

    private static String digits(String taxId) {
        return taxId == null ? null : taxId.replaceAll("\\D", "");
    }

    private static String limit(String description) {
        return description.length() <= 140 ? description : description.substring(0, 140);
    }

    private boolean commit(String messageKey, String messageType, Runnable changes) {
        return tx.inTransaction(() -> {
            if (!messageLog.markProcessed(messageKey, messageType)) {
                return false;
            }
            changes.run();
            return true;
        });
    }
}
