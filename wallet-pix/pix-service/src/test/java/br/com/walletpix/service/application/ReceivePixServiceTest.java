package br.com.walletpix.service.application;

import static br.com.walletpix.service.application.PixFixture.EXTERNAL_ISPB;
import static br.com.walletpix.service.application.PixFixture.OUR_ISPB;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.walletpix.messages.PixEvents.EventType;
import br.com.walletpix.messages.SpiMessages.Account;
import br.com.walletpix.messages.SpiMessages.Agent;
import br.com.walletpix.messages.SpiMessages.Amount;
import br.com.walletpix.messages.SpiMessages.AppHdr;
import br.com.walletpix.messages.SpiMessages.CdtTrfTxInf;
import br.com.walletpix.messages.SpiMessages.GrpHdr;
import br.com.walletpix.messages.SpiMessages.MsgType;
import br.com.walletpix.messages.SpiMessages.Pacs002;
import br.com.walletpix.messages.SpiMessages.Pacs008;
import br.com.walletpix.messages.SpiMessages.Party;
import br.com.walletpix.messages.SpiMessages.TxInfAndSts;
import br.com.walletpix.messages.SpiMessages.TxStatus;
import br.com.walletpix.service.application.MessageFailures.PermanentFailure;
import br.com.walletpix.service.application.MessageFailures.RetryLater;
import br.com.walletpix.service.domain.HolderCheckResult;
import br.com.walletpix.service.domain.PixPayment;
import br.com.walletpix.service.domain.PixPayment.Direction;
import br.com.walletpix.service.domain.PixPayment.Status;
import br.com.walletpix.service.domain.SpiIds;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ReceivePixServiceTest {

    private static final String BRANCH = "0001";
    private static final String ACCOUNT = "001000029"; // number 00100002 + check digit 9
    private static final String CPF = "52998224725";

    private final PixFixture f = new PixFixture();
    private final UUID walletAccount = UUID.randomUUID();

    @Test
    void acceptsValidPixAnswersAcspAndCreditsOnlyAfterSettlement() {
        f.registerHolder(BRANCH, ACCOUNT, CPF, walletAccount);
        var order = pacs008("150.00", ACCOUNT, CPF, "TRAN");

        f.receive.onPaymentOrder(order.hdr, order.body);

        Pacs002 answer = (Pacs002) f.sentToSpi.getFirst().document();
        assertThat(answer.txInfAndSts().txSts()).isEqualTo(TxStatus.ACSP);
        assertThat(answer.orgnlMsgId()).isEqualTo(order.body.grpHdr().msgId());
        assertThat(f.creditCalls).as("no credit before the SPI settles").isEmpty();

        f.router.onStatusReport(spiHdr(), settlement(order, TxStatus.ACSC, null));

        PixPayment p = f.stored(order.e2e, Direction.INBOUND);
        assertThat(p.status()).isEqualTo(Status.CREDITED);
        assertThat(f.creditCalls).containsExactly(order.e2e); // idempotency key = EndToEndId
        assertThat(f.creditedCentsByKey.get(order.e2e)).isEqualTo(15000);
        assertThat(f.events).extracting(e -> e.type()).containsExactly(EventType.PIX_RECEIVED);
    }

    @Test
    void duplicateSettlementCreditsOnce() {
        f.registerHolder(BRANCH, ACCOUNT, CPF, walletAccount);
        var order = pacs008("10.00", ACCOUNT, CPF, "TRAN");
        f.receive.onPaymentOrder(order.hdr, order.body);
        AppHdr acscHdr = spiHdr();
        Pacs002 acsc = settlement(order, TxStatus.ACSC, null);

        f.router.onStatusReport(acscHdr, acsc);
        f.router.onStatusReport(acscHdr, acsc);           // same message redelivered by SQS
        f.router.onStatusReport(spiHdr(), acsc);          // same report resent under a new message id

        assertThat(f.creditCalls).hasSize(1);
        assertThat(f.events).hasSize(1);
    }

    @Test
    void duplicatePaymentOrderDoesNotCheckTwiceAndRepeatsTheAnswer() {
        f.registerHolder(BRANCH, ACCOUNT, CPF, walletAccount);
        var order = pacs008("10.00", ACCOUNT, CPF, "TRAN");

        f.receive.onPaymentOrder(order.hdr, order.body);
        f.receive.onPaymentOrder(order.hdr, order.body); // SQS redelivery: same message id
        f.receive.onPaymentOrder(new AppHdr(EXTERNAL_ISPB, OUR_ISPB, SpiIds.newMessageId(EXTERNAL_ISPB),
                MsgType.PACS_008, Instant.now()), order.body); // same Pix, new message id

        assertThat(f.holderCheckCalls).isEqualTo(1);
        assertThat(f.sentToSpi).hasSize(2) // first answer + the repeat for the new message id
                .allSatisfy(m -> assertThat(((Pacs002) m.document()).txInfAndSts().txSts()).isEqualTo(TxStatus.ACSP));
    }

    @Test
    void rejectsWithCatalogReasonPerHolderCheckOutcome() {
        assertRejection(HolderCheckResult.Outcome.ACCOUNT_NOT_FOUND, "AC03");
        assertRejection(HolderCheckResult.Outcome.ACCOUNT_BLOCKED, "AC06");
        assertRejection(HolderCheckResult.Outcome.ACCOUNT_CLOSED, "AC07");
        assertRejection(HolderCheckResult.Outcome.TAX_ID_MISMATCH, "BE01");
    }

    @Test
    void rejectsWrongAccountTypeWithoutCallingWalletCore() {
        var order = pacs008("10.00", ACCOUNT, CPF, "CACC");

        f.receive.onPaymentOrder(order.hdr, order.body);

        assertThat(rsn(f.sentToSpi.getLast())).isEqualTo("AC14");
        assertThat(f.holderCheckCalls).isZero();
    }

    @Test
    void spiCancellationAfterAcceptanceCreditsNothing() {
        f.registerHolder(BRANCH, ACCOUNT, CPF, walletAccount);
        var order = pacs008("10.00", ACCOUNT, CPF, "TRAN");
        f.receive.onPaymentOrder(order.hdr, order.body);

        f.router.onStatusReport(spiHdr(), settlement(order, TxStatus.RJCT, "AB03"));

        assertThat(f.stored(order.e2e, Direction.INBOUND).status()).isEqualTo(Status.REJECTED);
        assertThat(f.creditCalls).isEmpty();
    }

    @Test
    void walletCoreOutageLeavesNoTraceSoTheRetryStartsClean() {
        f.registerHolder(BRANCH, ACCOUNT, CPF, walletAccount);
        var order = pacs008("10.00", ACCOUNT, CPF, "TRAN");
        f.receive.onPaymentOrder(order.hdr, order.body);
        Pacs002 acsc = settlement(order, TxStatus.ACSC, null);
        AppHdr hdr = spiHdr();

        f.walletCoreDown = true;
        assertThatThrownBy(() -> f.router.onStatusReport(hdr, acsc)).isInstanceOf(RetryLater.class);
        assertThat(f.stored(order.e2e, Direction.INBOUND).status()).isEqualTo(Status.ACCEPTED);

        f.walletCoreDown = false;
        f.router.onStatusReport(hdr, acsc); // the redelivery
        assertThat(f.stored(order.e2e, Direction.INBOUND).status()).isEqualTo(Status.CREDITED);
    }

    @Test
    void statusReportForUnknownOrderIsRetriedNotDropped() {
        assertThatThrownBy(() -> f.router.onStatusReport(spiHdr(), new Pacs002(new GrpHdr("M1", Instant.now()),
                "M-unknown", new TxInfAndSts("E1", TxStatus.ACSC, null, null)))).isInstanceOf(RetryLater.class);
    }

    @Test
    void orderForAnIspbThatIsNotOursIsAPermanentFailure() {
        var order = pacs008("10.00", ACCOUNT, CPF, "TRAN", "11111111");

        assertThatThrownBy(() -> f.receive.onPaymentOrder(order.hdr, order.body)).isInstanceOf(PermanentFailure.class);
    }

    // ------------------------------------------------------------------ helpers

    private void assertRejection(HolderCheckResult.Outcome outcome, String expectedCode) {
        String cpf = switch (outcome) {
            case ACCOUNT_NOT_FOUND -> "11144477735";
            case ACCOUNT_BLOCKED -> "39053344705";
            case ACCOUNT_CLOSED -> "16899535009";
            default -> "71428793860";
        };
        if (outcome != HolderCheckResult.Outcome.ACCOUNT_NOT_FOUND) {
            f.registerHolderOutcome(BRANCH, ACCOUNT, cpf, outcome);
        }
        var order = pacs008("10.00", ACCOUNT, cpf, "TRAN");

        f.receive.onPaymentOrder(order.hdr, order.body);

        assertThat(rsn(f.sentToSpi.getLast())).as(outcome.name()).isEqualTo(expectedCode);
        assertThat(f.stored(order.e2e, Direction.INBOUND).status()).isEqualTo(Status.REJECTED);
    }

    private static String rsn(br.com.walletpix.messages.SpiMessages.Envelope<?> m) {
        TxInfAndSts s = ((Pacs002) m.document()).txInfAndSts();
        assertThat(s.txSts()).isEqualTo(TxStatus.RJCT);
        return s.rsnCd();
    }

    record Order(String e2e, AppHdr hdr, Pacs008 body) {
    }

    private Order pacs008(String amount, String account, String cpf, String accountType) {
        return pacs008(amount, account, cpf, accountType, OUR_ISPB);
    }

    private Order pacs008(String amount, String account, String cpf, String accountType, String payeeIspb) {
        Instant now = f.clock.instant();
        String e2e = SpiIds.newEndToEndId(EXTERNAL_ISPB, now);
        String msgId = SpiIds.newMessageId(SpiMessageFactory.SPI_ISPB);
        Pacs008 body = new Pacs008(new GrpHdr(msgId, now), new CdtTrfTxInf(e2e, Amount.brl(new BigDecimal(amount)),
                new Party("Pagador Externo", "11144477735"), new Account("0042", "1234565", "TRAN"),
                new Agent(EXTERNAL_ISPB),
                new Party("Maria Silva", cpf), new Account(BRANCH, account, accountType), new Agent(payeeIspb),
                "teste"));
        return new Order(e2e, new AppHdr(SpiMessageFactory.SPI_ISPB, payeeIspb, msgId, MsgType.PACS_008, now), body);
    }

    private static AppHdr spiHdr() {
        return new AppHdr(SpiMessageFactory.SPI_ISPB, OUR_ISPB, SpiIds.newMessageId(SpiMessageFactory.SPI_ISPB),
                MsgType.PACS_002, Instant.now());
    }

    private static Pacs002 settlement(Order order, String txSts, String reason) {
        return new Pacs002(new GrpHdr(SpiIds.newMessageId(SpiMessageFactory.SPI_ISPB), Instant.now()),
                order.body.grpHdr().msgId(), new TxInfAndSts(order.e2e, txSts, reason, null));
    }
}
