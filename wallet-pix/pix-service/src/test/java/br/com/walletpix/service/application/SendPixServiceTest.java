package br.com.walletpix.service.application;

import static br.com.walletpix.service.application.PixFixture.EXTERNAL_ISPB;
import static br.com.walletpix.service.application.PixFixture.OUR_ISPB;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.walletpix.messages.PixEvents.EventType;
import br.com.walletpix.messages.SpiMessages.Amount;
import br.com.walletpix.messages.SpiMessages.AppHdr;
import br.com.walletpix.messages.SpiMessages.GrpHdr;
import br.com.walletpix.messages.SpiMessages.MsgType;
import br.com.walletpix.messages.SpiMessages.Pacs002;
import br.com.walletpix.messages.SpiMessages.Pacs004;
import br.com.walletpix.messages.SpiMessages.Pacs008;
import br.com.walletpix.messages.SpiMessages.TxInf;
import br.com.walletpix.messages.SpiMessages.TxInfAndSts;
import br.com.walletpix.messages.SpiMessages.TxStatus;
import br.com.walletpix.service.application.port.PixPorts;
import br.com.walletpix.service.application.MessageFailures.PermanentFailure;
import br.com.walletpix.service.application.MessageFailures.RetryLater;
import br.com.walletpix.service.application.SendPixService.InitiateCommand;
import br.com.walletpix.service.application.SendPixService.PaymentRefused;
import br.com.walletpix.service.domain.PixPayment;
import br.com.walletpix.service.domain.PixPayment.Direction;
import br.com.walletpix.service.domain.PixPayment.Status;
import br.com.walletpix.service.domain.SpiIds;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SendPixServiceTest {

    private static final String CPF = "52998224725";

    private final PixFixture f = new PixFixture();
    private final UUID payer = f.payerAccount("0001", "001000029", CPF, 100_00);

    @Test
    void debitsThenSendsPacs008WithSpiIdentifiers() {
        var result = f.send.initiate(command("req-1", "75.50"));

        assertThat(result.replayed()).isFalse();
        assertThat(f.balances.get(payer)).isEqualTo(24_50);
        PixPayment p = result.payment();
        assertThat(p.status()).isEqualTo(Status.SENT);
        assertThat(p.debitTransactionId()).isEqualTo(f.debitsByKey.get("pix-debit-req-1"));
        assertThat(p.payer().name()).as("payer name comes from wallet-core").isEqualTo("Maria Silva");

        Pacs008 order = (Pacs008) f.sentToSpi.getFirst().document();
        assertThat(f.sentToSpi.getFirst().appHdr().to()).isEqualTo(SpiMessageFactory.SPI_ISPB);
        assertThat(order.cdtTrfTxInf().endToEndId()).startsWith("E" + OUR_ISPB + "202610041200");
        assertThat(SpiIds.isEndToEndId(order.cdtTrfTxInf().endToEndId())).isTrue();
        assertThat(order.cdtTrfTxInf().intrBkSttlmAmt().amt()).isEqualByComparingTo("75.50");
        assertThat(order.cdtTrfTxInf().dbtr().id()).isEqualTo(CPF);

        // wallet-core records the debit as a PIX_OUT with the same EndToEndId and the payee
        var pix = f.pixRecordsByKey.get("pix-debit-req-1");
        assertThat(pix.endToEndId()).isEqualTo(order.cdtTrfTxInf().endToEndId());
        assertThat(pix.counterparty().name()).isEqualTo("João Externo");
        assertThat(pix.counterparty().ispb()).isEqualTo(EXTERNAL_ISPB);
        assertThat(pix.counterparty().taxId()).isEqualTo("11144477735");
        assertThat(pix.remittanceInfo()).isEqualTo("aluguel");
    }

    @Test
    void retryAfterACrashSendsTheEndToEndIdOfTheFirstDebit() {
        // First attempt debited, then the process died before the payment was stored.
        String firstE2e = "E" + OUR_ISPB + "202610041159abcdefghijk";
        f.walletCore.debit(OUR_ISPB, payer, 75_50, "Pix enviado para João Externo", "pix-debit-req-1",
                PixPorts.PixRecord.of(firstE2e, null, null));

        PixPayment p = f.send.initiate(command("req-1", "75.50")).payment();

        assertThat(p.endToEndId()).isEqualTo(firstE2e);
        assertThat(((Pacs008) f.sentToSpi.getFirst().document()).cdtTrfTxInf().endToEndId()).isEqualTo(firstE2e);
        assertThat(f.balances.get(payer)).as("debited once").isEqualTo(24_50);
    }

    @Test
    void insufficientFundsRefusesSynchronouslyAndSendsNothing() {
        assertThatThrownBy(() -> f.send.initiate(command("req-1", "100.01")))
                .isInstanceOfSatisfying(PaymentRefused.class, e -> assertThat(e.code()).isEqualTo("INSUFFICIENT_FUNDS"));

        assertThat(f.sentToSpi).isEmpty();
        assertThat(f.paymentsByKey).isEmpty();
        assertThat(f.balances.get(payer)).isEqualTo(100_00);
    }

    @Test
    void policyRefusalHappensBeforeAnyCallToWalletCore() {
        SendPixService withLimit = new SendPixService(f.walletCore, f.payments, f.messageLog, f.outbound, f.tx,
                f.participants, java.util.List.of(new MaxAmountPolicy(new BigDecimal("50.00"))), f.metrics, f.clock);

        assertThatThrownBy(() -> withLimit.initiate(command("req-1", "50.01")))
                .isInstanceOfSatisfying(PaymentRefused.class, e -> assertThat(e.code()).isEqualTo("POLICY_MAX_AMOUNT"));
        assertThat(f.debitCalls).isZero();
        assertThat(f.holderCheckCalls).isZero();
    }

    @Test
    void payerDocumentMustBeTheHolders() {
        InitiateCommand c = command("req-1", "10.00");
        InitiateCommand wrongDocument = new InitiateCommand(c.ispb(), c.requestId(), c.payerAccountId(), "11144477735",
                c.payeeIspb(), c.payeeBranch(), c.payeeAccountNumber(), c.payeeTaxId(), c.payeeName(), c.amount(),
                c.description());

        assertThatThrownBy(() -> f.send.initiate(wrongDocument))
                .isInstanceOfSatisfying(PaymentRefused.class, e -> assertThat(e.code()).startsWith("PAYER_"));
        assertThat(f.debitCalls).isZero();
    }

    @Test
    void sameIdempotencyKeyDebitsAndSendsOnce() {
        var first = f.send.initiate(command("req-1", "10.00"));
        var second = f.send.initiate(command("req-1", "10.00"));

        assertThat(second.replayed()).isTrue();
        assertThat(second.payment().endToEndId()).isEqualTo(first.payment().endToEndId());
        assertThat(f.sentToSpi).hasSize(1);
        assertThat(f.balances.get(payer)).isEqualTo(90_00);
    }

    @Test
    void sameIdempotencyKeyForAnotherPaymentIsAConflict() {
        f.send.initiate(command("req-1", "10.00"));

        assertThatThrownBy(() -> f.send.initiate(command("req-1", "11.00")))
                .isInstanceOfSatisfying(PaymentRefused.class,
                        e -> assertThat(e.code()).isEqualTo("IDEMPOTENCY_KEY_REUSED"));
    }

    @Test
    void walletCoreOutageDuringDebitIsRetryableAndTheRetryDebitsOnce() {
        f.walletCoreDown = true;
        assertThatThrownBy(() -> f.send.initiate(command("req-1", "10.00"))).isInstanceOf(RetryLater.class);
        f.walletCoreDown = false;

        f.send.initiate(command("req-1", "10.00"));

        assertThat(f.balances.get(payer)).isEqualTo(90_00);
        assertThat(f.sentToSpi).hasSize(1);
    }

    @Test
    void settlementCompletesThePayment() {
        PixPayment sent = f.send.initiate(command("req-1", "10.00")).payment();

        f.router.onStatusReport(spiHdr(), report(sent, TxStatus.ACSC, null));

        assertThat(f.stored(sent.endToEndId(), Direction.OUTBOUND).status()).isEqualTo(Status.COMPLETED);
        assertThat(f.events).extracting(e -> e.type()).containsExactly(EventType.PIX_SENT_COMPLETED);
        assertThat(f.reversalsByDebit).isEmpty();
    }

    @Test
    void rejectionReversesTheDebitOnceEvenIfRedelivered() {
        PixPayment sent = f.send.initiate(command("req-1", "10.99")).payment();
        AppHdr hdr = spiHdr();
        Pacs002 rjct = report(sent, TxStatus.RJCT, "AC03");

        f.router.onStatusReport(hdr, rjct);
        f.router.onStatusReport(hdr, rjct);

        PixPayment refunded = f.stored(sent.endToEndId(), Direction.OUTBOUND);
        assertThat(refunded.status()).isEqualTo(Status.REFUNDED);
        assertThat(refunded.reasonCode()).isEqualTo("AC03");
        assertThat(refunded.walletTransactionId()).isEqualTo(f.reversalsByDebit.get(sent.debitTransactionId()));
        assertThat(f.balances.get(payer)).isEqualTo(100_00);
        assertThat(f.creditCalls).as("a rejection is undone by reversal, not by a new deposit").isEmpty();
        assertThat(f.reversalReasons.get(sent.debitTransactionId())).isEqualTo("AC03");
        assertThat(f.events).extracting(e -> e.type()).containsExactly(EventType.PIX_SENT_REFUNDED);
    }

    @Test
    void returnOfACompletedPixCreditsThePayer() {
        PixPayment sent = f.send.initiate(command("req-1", "50.00")).payment();
        f.router.onStatusReport(spiHdr(), report(sent, TxStatus.ACSC, null));

        String rtrId = "D" + EXTERNAL_ISPB + "202610041201abcdefghijk";
        f.send.onReturn(spiHdr(MsgType.PACS_004), new Pacs004(new GrpHdr("M-ret", Instant.now()),
                new TxInf(rtrId, sent.endToEndId(), Amount.brl(new BigDecimal("20.00")), "MD06")));

        assertThat(f.stored(sent.endToEndId(), Direction.OUTBOUND).status()).isEqualTo(Status.RETURNED);
        assertThat(f.creditedCentsByKey.get("pix-return-" + rtrId)).isEqualTo(2000);
        var pix = f.pixRecordsByKey.get("pix-return-" + rtrId);
        assertThat(pix.returnId()).isEqualTo(rtrId);
        assertThat(pix.endToEndId()).isEqualTo(sent.endToEndId());
        assertThat(pix.relatedTransactionId()).as("tied to the original debit").isEqualTo(sent.debitTransactionId());
        assertThat(pix.reasonCode()).isEqualTo("MD06");
        assertThat(pix.counterparty().name()).isEqualTo("João Externo");
        assertThat(f.events).extracting(e -> e.type())
                .containsExactly(EventType.PIX_SENT_COMPLETED, EventType.PIX_RETURN_RECEIVED);
    }

    @Test
    void returnLargerThanTheOriginalIsRejected() {
        PixPayment sent = f.send.initiate(command("req-1", "50.00")).payment();
        f.router.onStatusReport(spiHdr(), report(sent, TxStatus.ACSC, null));

        assertThatThrownBy(() -> f.send.onReturn(spiHdr(MsgType.PACS_004), new Pacs004(new GrpHdr("M-ret", Instant.now()),
                new TxInf("D1", sent.endToEndId(), Amount.brl(new BigDecimal("50.01")), "MD06"))))
                .isInstanceOf(PermanentFailure.class);
        assertThat(f.creditCalls).isEmpty();
    }

    @Test
    void tenantThatIsNotAParticipantIsRefused() {
        InitiateCommand c = command("req-1", "10.00");
        InitiateCommand foreign = new InitiateCommand("11111111", c.requestId(), c.payerAccountId(), c.payerTaxId(),
                c.payeeIspb(), c.payeeBranch(), c.payeeAccountNumber(), c.payeeTaxId(), c.payeeName(), c.amount(),
                c.description());

        assertThatThrownBy(() -> f.send.initiate(foreign))
                .isInstanceOfSatisfying(PaymentRefused.class, e -> assertThat(e.code()).isEqualTo("NOT_A_PARTICIPANT"));
    }

    // ------------------------------------------------------------------ helpers

    private InitiateCommand command(String requestId, String amount) {
        return new InitiateCommand(OUR_ISPB, requestId, payer, "529.982.247-25", EXTERNAL_ISPB, "0042", "1234565",
                "11144477735", "João Externo", new BigDecimal(amount), "aluguel");
    }

    private static AppHdr spiHdr() {
        return spiHdr(MsgType.PACS_002);
    }

    private static AppHdr spiHdr(String type) {
        return new AppHdr(SpiMessageFactory.SPI_ISPB, OUR_ISPB, SpiIds.newMessageId(SpiMessageFactory.SPI_ISPB), type,
                Instant.now());
    }

    private static Pacs002 report(PixPayment sent, String txSts, String reason) {
        return new Pacs002(new GrpHdr(SpiIds.newMessageId(SpiMessageFactory.SPI_ISPB), Instant.now()),
                sent.pacs008MsgId(), new TxInfAndSts(sent.endToEndId(), txSts, reason, null));
    }
}
