package br.com.walletpix.service.application;

import br.com.walletpix.messages.SpiMessages.Account;
import br.com.walletpix.messages.SpiMessages.Agent;
import br.com.walletpix.messages.SpiMessages.Amount;
import br.com.walletpix.messages.SpiMessages.AppHdr;
import br.com.walletpix.messages.SpiMessages.CdtTrfTxInf;
import br.com.walletpix.messages.SpiMessages.Envelope;
import br.com.walletpix.messages.SpiMessages.GrpHdr;
import br.com.walletpix.messages.SpiMessages.MsgType;
import br.com.walletpix.messages.SpiMessages.Pacs002;
import br.com.walletpix.messages.SpiMessages.Pacs008;
import br.com.walletpix.messages.SpiMessages.Party;
import br.com.walletpix.messages.SpiMessages.TxInfAndSts;
import br.com.walletpix.service.domain.Amounts;
import br.com.walletpix.service.domain.PixPayment;
import br.com.walletpix.service.domain.SpiIds;
import java.time.Instant;

/** Builds the messages this PSP sends to the SPI. */
final class SpiMessageFactory {

    /** ISPB of the Banco Central do Brasil, operator of the SPI: the "To" of everything we send. */
    static final String SPI_ISPB = "00038166";

    static final String ACCOUNT_TYPE = "TRAN";

    private SpiMessageFactory() {
    }

    /** Our answer, as the payee's PSP, to a pacs.008: ACSP, or RJCT with a reason. */
    static Envelope<Pacs002> statusReport(String ourIspb, String originalMsgId, String endToEndId, String txSts,
                                          String reasonCode, String additionalInfo, Instant now) {
        String msgId = SpiIds.newMessageId(ourIspb);
        return new Envelope<>(
                new AppHdr(ourIspb, SPI_ISPB, msgId, MsgType.PACS_002, now),
                new Pacs002(new GrpHdr(msgId, now), originalMsgId,
                        new TxInfAndSts(endToEndId, txSts, reasonCode, additionalInfo)));
    }

    /** The payment order of an outgoing Pix; the payment's {@code pacs008MsgId} is reused as the message id. */
    static Envelope<Pacs008> paymentOrder(PixPayment p, Instant now) {
        return new Envelope<>(
                new AppHdr(p.ispb(), SPI_ISPB, p.pacs008MsgId(), MsgType.PACS_008, now),
                new Pacs008(new GrpHdr(p.pacs008MsgId(), now), new CdtTrfTxInf(
                        p.endToEndId(),
                        Amount.brl(Amounts.toDecimal(p.amountCents())),
                        new Party(p.payer().name(), p.payer().taxId()),
                        new Account(p.payer().branch(), p.payer().accountNumber(), ACCOUNT_TYPE),
                        new Agent(p.ispb()),
                        new Party(p.payee().name(), p.payee().taxId()),
                        new Account(p.payee().branch(), p.payee().accountNumber(), ACCOUNT_TYPE),
                        new Agent(p.counterpartIspb()),
                        p.description())));
    }
}
