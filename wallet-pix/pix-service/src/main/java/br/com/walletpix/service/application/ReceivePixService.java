package br.com.walletpix.service.application;

import br.com.walletpix.messages.PixEvents.EventType;
import br.com.walletpix.messages.SpiMessages.AppHdr;
import br.com.walletpix.messages.SpiMessages.CdtTrfTxInf;
import br.com.walletpix.messages.SpiMessages.Envelope;
import br.com.walletpix.messages.SpiMessages.MsgType;
import br.com.walletpix.messages.SpiMessages.Pacs008;
import br.com.walletpix.messages.SpiMessages.TxInfAndSts;
import br.com.walletpix.messages.SpiMessages.TxStatus;
import br.com.walletpix.service.application.MessageFailures.PermanentFailure;
import br.com.walletpix.service.application.port.PixPorts.Counterparty;
import br.com.walletpix.service.application.port.PixPorts.InboundMessageLog;
import br.com.walletpix.service.application.port.PixPorts.OutboundMessages;
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
import br.com.walletpix.service.domain.RejectionReason;
import br.com.walletpix.service.domain.SpiIds;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Incoming Pix - we are the payee's PSP.
 * <ol>
 *   <li>pacs.008 arrives: check the account type, then ask wallet-core whether branch + account +
 *       CPF/CNPJ match an account able to receive; answer pacs.002 ACSP, or RJCT with
 *       AC03/AC06/AC07/AC14/BE01.</li>
 *   <li>The SPI settles and sends pacs.002 ACSC: credit the customer (idempotency key = EndToEndId).
 *       A RJCT instead (the SPI gave up, e.g. on a timeout) just closes the payment, nothing credited.</li>
 * </ol>
 * Calls to wallet-core happen outside the database transaction; the transaction only records
 * the decision, the message-log entry and the outbound messages, so it stays short and a
 * failure anywhere before commit simply replays the message.
 */
public final class ReceivePixService {

    /** Account id on the bus = account number with the check digit appended (see SpiMessages.Account). */
    private static final Pattern ACCOUNT_WITH_DIGIT = Pattern.compile("\\d{2,21}");

    private final WalletCore walletCore;
    private final PixPaymentRepository payments;
    private final InboundMessageLog messageLog;
    private final OutboundMessages outbound;
    private final Transactions tx;
    private final Participants participants;
    private final PixMetrics metrics;
    private final Clock clock;

    public ReceivePixService(WalletCore walletCore, PixPaymentRepository payments, InboundMessageLog messageLog,
                             OutboundMessages outbound, Transactions tx, Participants participants, PixMetrics metrics,
                             Clock clock) {
        this.walletCore = walletCore;
        this.payments = payments;
        this.messageLog = messageLog;
        this.outbound = outbound;
        this.tx = tx;
        this.participants = participants;
        this.metrics = metrics;
        this.clock = clock;
    }

    // ------------------------------------------------------------------ step 1: authorize (pacs.008)

    public void onPaymentOrder(AppHdr hdr, Pacs008 order) {
        String messageKey = hdr.bizMsgIdr();
        if (messageLog.alreadyProcessed(messageKey)) {
            metrics.inboundMessage(MsgType.PACS_008, "duplicate");
            return;
        }
        CdtTrfTxInf t = order.cdtTrfTxInf();
        String ourIspb = t.cdtrAgt().ispb();
        if (!SpiIds.isEndToEndId(t.endToEndId())) {
            throw new PermanentFailure("malformed EndToEndId: " + t.endToEndId());
        }
        if (!participants.isOurs(ourIspb) || !ourIspb.equals(hdr.to())) {
            throw new PermanentFailure("pacs.008 for ISPB " + ourIspb + " (to " + hdr.to() + ") is not ours");
        }

        Optional<PixPayment> existing = payments.find(t.endToEndId(), Direction.INBOUND);
        if (existing.isPresent()) {
            // The same Pix again under a new message id: repeat the decision already taken,
            // never decide (or run the holder check) twice for one EndToEndId.
            Instant now = clock.instant();
            commit(messageKey, MsgType.PACS_008, () -> outbound.sendToSpi(answer(existing.get(), now)));
            metrics.inboundMessage(MsgType.PACS_008, "repeated");
            return;
        }

        PixPayment decision = decide(order, ourIspb, clock.instant());
        boolean committed = commit(messageKey, MsgType.PACS_008, () -> {
            payments.insert(decision);
            outbound.sendToSpi(answer(decision, decision.createdAt()));
            if (decision.status() == Status.REJECTED) {
                outbound.publishEvent(PixEventFactory.of(EventType.PIX_RECEIVE_REJECTED, decision));
            }
        });
        if (committed) {
            metrics.inboundMessage(MsgType.PACS_008, decision.status().name().toLowerCase());
            metrics.payment(decision);
        }
    }

    private PixPayment decide(Pacs008 order, String ourIspb, Instant now) {
        CdtTrfTxInf t = order.cdtTrfTxInf();
        long cents = Amounts.toCents(t.intrBkSttlmAmt().amt());
        PartyAccount payer = new PartyAccount(t.dbtr().nm(), t.dbtr().id(), t.dbtrAcct().issr(), t.dbtrAcct().id());
        PartyAccount payee = new PartyAccount(t.cdtr().nm(), t.cdtr().id(), t.cdtrAcct().issr(), t.cdtrAcct().id());
        String msgId = order.grpHdr().msgId();

        Optional<RejectionReason> earlyRejection = Optional.empty();
        UUID accountId = null;
        String account = t.cdtrAcct().id();
        if (!SpiMessageFactory.ACCOUNT_TYPE.equals(t.cdtrAcct().tp())) {
            earlyRejection = Optional.of(RejectionReason.AC14); // we only hold payment accounts (TRAN)
        } else if (account == null || !ACCOUNT_WITH_DIGIT.matcher(account).matches()) {
            earlyRejection = Optional.of(RejectionReason.AC03);
        } else {
            HolderCheckResult check = walletCore.checkHolder(ourIspb, t.cdtrAcct().issr(),
                    account.substring(0, account.length() - 1), account.substring(account.length() - 1),
                    t.cdtr().id());
            earlyRejection = check.rejectionReason();
            accountId = check.accountId();
        }
        if (earlyRejection.isPresent()) {
            return PixPayment.rejectedIncoming(t.endToEndId(), ourIspb, t.dbtrAgt().ispb(), msgId, cents, payer, payee,
                    earlyRejection.get(), t.rmtInf(), now);
        }
        return PixPayment.acceptedIncoming(t.endToEndId(), ourIspb, t.dbtrAgt().ispb(), msgId, cents, payer, payee,
                accountId, t.rmtInf(), now);
    }

    private static Envelope<?> answer(PixPayment p, Instant now) {
        if (p.status() == Status.REJECTED) {
            return SpiMessageFactory.statusReport(p.ispb(), p.pacs008MsgId(), p.endToEndId(), TxStatus.RJCT,
                    p.reasonCode(), describe(p.reasonCode()), now);
        }
        return SpiMessageFactory.statusReport(p.ispb(), p.pacs008MsgId(), p.endToEndId(), TxStatus.ACSP, null, null,
                now);
    }

    /** Our own reasons have a description; one set by the SPI (cancelledBySpi) is passed through as is. */
    private static String describe(String reasonCode) {
        try {
            return RejectionReason.valueOf(reasonCode).description();
        } catch (IllegalArgumentException | NullPointerException notOurs) {
            return null;
        }
    }

    // ------------------------------------------------------------------ step 2: settlement (pacs.002 from the SPI)

    /** Called by {@link StatusReportRouter} once it knows the report is about an INBOUND payment. */
    void onStatusReport(AppHdr hdr, PixPayment payment, TxInfAndSts status) {
        String messageKey = hdr.bizMsgIdr();
        switch (status.txSts()) {
            case TxStatus.ACSC -> settle(messageKey, payment);
            case TxStatus.RJCT -> cancel(messageKey, payment, status.rsnCd());
            default -> commit(messageKey, MsgType.PACS_002, () -> { }); // nothing to do for this leg
        }
    }

    private void settle(String messageKey, PixPayment payment) {
        if (payment.status() != Status.ACCEPTED) {
            // CREDITED already (duplicate ACSC) or REJECTED (ACSC for a Pix we refused - the SPI
            // would never do that): nothing to credit, just remember the message.
            commit(messageKey, MsgType.PACS_002, () -> { });
            metrics.inboundMessage(MsgType.PACS_002, "ignored-" + payment.status().name().toLowerCase());
            return;
        }
        // Idempotency key = EndToEndId: if a previous attempt credited and then crashed before
        // committing below, this returns the same wallet-core transaction instead of paying twice.
        PartyAccount payer = payment.payer();
        UUID creditId = walletCore.credit(payment.ispb(), payment.walletAccountId(), payment.amountCents(),
                creditDescription(payment), payment.endToEndId(), PixRecord.of(payment.endToEndId(),
                        new Counterparty(payer.name(), payer.taxId(), payment.counterpartIspb(), payer.branch(),
                                payer.accountNumber()),
                        payment.description()));
        PixPayment credited = payment.credited(creditId, clock.instant());
        boolean committed = commit(messageKey, MsgType.PACS_002, () -> {
            payments.update(credited);
            outbound.publishEvent(PixEventFactory.of(EventType.PIX_RECEIVED, credited));
        });
        if (committed) {
            metrics.inboundMessage(MsgType.PACS_002, "credited");
            metrics.payment(credited);
        }
    }

    private void cancel(String messageKey, PixPayment payment, String spiReason) {
        if (payment.status() != Status.ACCEPTED) {
            commit(messageKey, MsgType.PACS_002, () -> { });
            return;
        }
        PixPayment cancelled = payment.cancelledBySpi(spiReason, clock.instant());
        commit(messageKey, MsgType.PACS_002, () -> {
            payments.update(cancelled);
            outbound.publishEvent(PixEventFactory.of(EventType.PIX_RECEIVE_REJECTED, cancelled));
        });
        metrics.payment(cancelled);
    }

    /** Shown in the customer's statement; wallet-core caps descriptions at 140 characters. */
    private static String creditDescription(PixPayment p) {
        String text = "Pix recebido de " + p.payer().name() + " - " + p.endToEndId();
        return text.length() <= 140 ? text : text.substring(0, 140);
    }

    /** Runs {@code changes} together with recording the message; false if another delivery got there first. */
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
